/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.browser.domain.SlowResponse
import az.petek.campaign.domain.FlowNames
import az.petek.core.model.RegistrationMode
import az.petek.evidence.domain.StepStatus
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/**
 * `site_health`: blind checks that need no knowledge of the site (docs/PLAN.md Faza 13), decided by code from what the
 * browser saw. For every page of `pages` (path keys or `/paths`, comma-separated; default `home`) it runs the `checks`
 * asked (comma-separated; default: all but `look`):
 *
 * - `links`: every link of the page that stays on the site answers below 400 (up to `max_links`, default 30);
 * - `console`: no `console.error` or uncaught exception, and no request to the site failed (4xx/5xx or no answer);
 * - `slow`: no request to the site took `slow_ms` (default 3000) or more;
 * - `back`: after following the page's first link, the back button returns to the page;
 * - `mobile`: at `width` (default 375) × 812 the page is not wider than the screen;
 * - `session`: with the cookies gone (an expired session) the page no longer shows the signed-in user; the tester then
 *   signs in again with the `login` flow. Visitors and signed-out testers skip it;
 * - `perf`: how fast the page became usable, as the browser timed it (first byte, DOM ready, load, largest contentful
 *   paint, layout shift), is recorded per page and screen ([az.petek.evidence.domain.PageTimingRecord]). It never fails
 *   the step: `petek compare` sets it against an earlier release (the regression baseline, Faza 14);
 * - `look`: how the page looks on the job's screen, taken by code for comparing releases (docs/adr/0014): settled, at
 *   the top, down to `look_max_height`, loaded `look_loads` times ([LookRequests]), kept as visual frames and a
 *   [az.petek.evidence.domain.PageLookRecord]. The owner's `target_profile.visual.mask`, the step's `look_mask` and the
 *   run's own texts ([RunTexts]) are not compared. Only asked for by name, and it never fails the step: a look the
 *   browser cannot take, or whose frames cannot be kept, is a skipped sub-action. At most [PageShare.MAX_LOOKS_PER_JOB]
 *   testers look at one page on one screen.
 *
 * With `share: work` the step's testers split the pages and devices between them (each job a page on a phone, tablet
 * or desktop), with `share: pages` the pages, and with `share: links` each checks every page but asks about only its
 * share of the links ([PageShare]).
 *
 * Every problem is listed in the outcome (`unhealthy_page`), each once per job: a look's reload repeats the page's own
 * console errors and requests. The browser ends on the first page that went wrong, so the step's screenshot shows it.
 * A clean site passes.
 */
internal class SiteHealthRunFunction(
    private val engine: RunEngine,
    private val flows: FlowRunner,
) : RunFunction {
    override val name: String = RunFunctions.SITE_HEALTH

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            val checks = list(args["checks"]).ifEmpty { DEFAULT_CHECKS }.toSet()
            val unknown = checks - ALL_CHECKS.toSet()
            if (unknown.isNotEmpty()) {
                throw RunFailure(
                    FailureReason.MISSING_PREREQUISITE,
                    "site_health knows ${ALL_CHECKS.joinToString()}, not ${unknown.joinToString()}",
                )
            }
            PageShare.problem(args)?.let { throw RunFailure(FailureReason.MISSING_PREREQUISITE, "site_health: $it") }
            val lookRequest =
                if (LOOK in checks) {
                    LookRequests.problem(runtime.target, args)?.let {
                        throw RunFailure(FailureReason.MISSING_PREREQUISITE, "site_health: $it")
                    }
                    LookRequests.of(runtime, args)
                } else {
                    null
                }
            val jobs = PageShare.jobs(list(args["pages"]).ifEmpty { listOf("home") }, args, step.share)
            val slow = (args["slow_ms"]?.toLongOrNull() ?: DEFAULT_SLOW_MS).milliseconds
            val width = args["width"]?.toIntOrNull() ?: DEFAULT_WIDTH
            val maxLinks = args["max_links"]?.toIntOrNull() ?: DEFAULT_MAX_LINKS
            val problems = mutableListOf<String>()
            val session = runtime.session
            val screens = Screens(this)
            var firstFailing: PageShare.Job? = null
            var looks = 0
            var looksMissed = 0
            try {
                for (job in jobs) {
                    screens.show(job.device)
                    val ref = job.page
                    val path = runtime.target.resolvePath(ref)
                    val where = job.where(path)
                    val since = now()
                    val before = problems.size
                    open(ref)
                    // Read before the other checks: following a link and coming back would time that return instead.
                    if ("perf" in checks) timing(path, job.device?.key)
                    // After the timing (a reload would time itself) and before any link is followed: the page as it opened.
                    if (lookRequest != null && job.lookRound < PageShare.MAX_LOOKS_PER_JOB) {
                        if (look(path, job.device?.key, lookRequest) != null) looks++ else looksMissed++
                    }
                    val links =
                        if ("links" in checks ||
                            "back" in checks
                        ) {
                            act("read the links of $path") { session.links() }
                        } else {
                            emptyList()
                        }
                    if ("links" in checks && job.asksLinks) {
                        // Only paths on the site itself: links() never gives another origin, and a full URL is not followed.
                        val own = links.filter { it.startsWith("/") && !it.startsWith("//") }.take(maxLinks)
                        PageShare.links(own, args, step.share).forEach { link ->
                            val status = probe("check link $link", { session.request("GET", link).status }) { it < BROKEN }
                            if (status >= BROKEN) problems += "$path links to $link, which answers $status"
                        }
                    }
                    if ("back" in checks) {
                        // Where the browser really is: the site may have redirected the page (`/docs` to `/docs/`).
                        val landed = pathOf(currentUrl())
                        links.firstOrNull { !samePath(pathOf(it), landed) }?.let { next -> checkBack(landed, next, where, problems) }
                    }
                    if ("mobile" in checks) checkWidth(job, path, where, width, problems)
                    val health = act("read what the page reported") { session.health(since, slow) }
                    // Once each: a look's reload repeats what the first load reported.
                    if ("console" in checks) {
                        health.consoleErrors.distinct().forEach { problems += "$where: console error: $it" }
                        health.failedRequests.distinct().forEach { problems += "$where: request failed: $it" }
                    }
                    if ("slow" in checks) {
                        health.slowResponses
                            .groupBy { it.method to it.path }
                            .map { (_, same) -> same.maxBy(SlowResponse::millis) }
                            .forEach { problems += "$where: ${it.describe()}" }
                    }
                    if (problems.size > before && firstFailing == null) firstFailing = job
                }
                if ("session" in checks) checkSessionExpiry(jobs.first().page, problems)
                val looked =
                    when {
                        lookRequest == null -> ""
                        looksMissed == 0 -> " Kept $looks look(s)."
                        else -> " Kept $looks look(s); $looksMissed could not be taken or kept."
                    }
                if (problems.isEmpty()) {
                    succeeded("Checked ${jobs.size} page(s) for ${checks.sorted().joinToString()}: nothing wrong.$looked")
                } else {
                    // The step's evidence is the page the browser ends on: the first page that went wrong, on its screen.
                    firstFailing?.takeIf { it != jobs.last() }?.let { job ->
                        screens.show(job.device)
                        open(job.page)
                    }
                    captureScreenshot()
                    note("problems found", StepStatus.FAILED, problems.joinToString("; "))
                    failed(FailureReason.UNHEALTHY_PAGE, "${problems.size} problem(s): " + problems.take(MAX_LISTED).joinToString("; "))
                }
            } finally {
                screens.restore()
            }
        }

    /** The page's width on a phone or tablet: at the job's device, or at `width` without one; a desktop is not measured. */
    private suspend fun RunTrace.checkWidth(
        job: PageShare.Job,
        path: String,
        where: String,
        width: Int,
        problems: MutableList<String>,
    ) {
        val device = job.device
        if (device == Device.DESKTOP) return
        val screen = device?.width ?: width
        val overflow = act("measure $path at ${screen}px") { runtime.session.horizontalOverflow(screen, device?.height ?: PHONE_HEIGHT) }
        if (overflow != null && overflow > TOLERANCE_PX) problems += "$where is $overflow px wider than a ${screen}px screen"
    }

    private suspend fun RunTrace.checkBack(
        path: String,
        next: String,
        where: String,
        problems: MutableList<String>,
    ) {
        openUrl(next)
        val went = act("press the back button") { runtime.session.goBack() }
        val back = pathOf(currentUrl())
        val on = where.removePrefix(path)
        if (went && !samePath(back, path)) problems += "the back button from $next$on leads to $back, not $path"
        if (!went) problems += "the back button from $next$on did nothing"
    }

    private suspend fun RunTrace.checkSessionExpiry(
        ref: String,
        problems: MutableList<String>,
    ) {
        val identity = runtime.identity
        if (identity.registration == RegistrationMode.GUEST || !isVisible(TargetFlows.USER_NAME)) {
            note("session expiry skipped: not signed in", StepStatus.PASSED)
            return
        }
        act("forget the session's cookies") { runtime.session.clearCookies() }
        open(ref)
        if (isVisible(TargetFlows.USER_NAME)) {
            problems += "${runtime.target.resolvePath(ref)} still shows the signed-in user after the session's cookies were cleared"
            return
        }
        flows.run(this, FlowNames.LOGIN, FlowProgress(), FailureReason.LOGIN_FAILED)
    }

    private fun list(text: String?): List<String> =
        text
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** One page, whether or not the address ends with a slash (`/docs` and `/docs/`). */
    private fun samePath(
        a: String,
        b: String,
    ): Boolean = a.trimEnd('/') == b.trimEnd('/')

    private fun pathOf(url: String): String =
        runCatching { URI(url).rawPath }.getOrNull()?.ifEmpty { "/" }
            ?: url.substringBefore('?')

    companion object {
        const val LOOK = "look"
        val ALL_CHECKS: List<String> = listOf("links", "console", "slow", "back", "mobile", "session", "perf", LOOK)

        /** The checks of a step that names none: a look is taken only when asked for. */
        val DEFAULT_CHECKS: List<String> = ALL_CHECKS - LOOK
        const val DEFAULT_SLOW_MS = 3_000L
        const val DEFAULT_WIDTH = 375
        const val DEFAULT_MAX_LINKS = 30
        private const val PHONE_HEIGHT = 812
        private const val TOLERANCE_PX = 1
        private const val BROKEN = 400
        private const val MAX_LISTED = 10
    }
}
