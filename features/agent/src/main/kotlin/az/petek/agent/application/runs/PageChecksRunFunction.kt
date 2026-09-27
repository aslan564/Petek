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
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.PageFacts
import az.petek.evidence.domain.StepStatus
import java.net.URI

/**
 * `page_checks`: what a visitor sees on each page, checked by code (Faza 19, the showcase cards). Only reads, so it
 * may run in a visitor run. For every page of `pages` (path keys or `/paths`, comma-separated; default `home`) it runs
 * the `checks` asked (comma-separated; default all):
 *
 * - `anchors`: every in-page link (`#section`) names an element that exists;
 * - `images`: every image that finished loading shows pixels (none is broken);
 * - `alt`: every image has an `alt` text (an empty one marks a decorative image and counts);
 * - `meta`: the page has a title, one main heading (`h1`), a description and a language, and no two pages share a
 *   title;
 * - `outbound`: the links to other sites answer; up to `max_outbound` (default 40) distinct addresses are asked once,
 *   and only an answer of 404 or 410, or none at all, is a dead link: 401, 403, 405, 429, 999 and 5xx mean the other
 *   site refuses automated visitors, which is noted, not reported.
 *
 * With `share: work` the step's testers split the pages and devices between them (each job a page on a phone, tablet
 * or desktop), with `share: pages` the pages (duplicate titles are then compared within a tester's pages only), and
 * with `share: links` each checks every page but asks about only its share of the links to other sites ([PageShare]).
 *
 * Every problem is listed in the outcome (`unhealthy_page`, a finding about the site); the browser ends on the first
 * page that went wrong, so the step's screenshot shows it. A clean site passes.
 */
internal class PageChecksRunFunction(
    private val engine: RunEngine,
) : RunFunction {
    override val name: String = RunFunctions.PAGE_CHECKS

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            val checks = list(args["checks"]).ifEmpty { ALL_CHECKS }.toSet()
            val unknown = checks - ALL_CHECKS.toSet()
            if (unknown.isNotEmpty()) {
                val known = ALL_CHECKS.joinToString()
                throw RunFailure(FailureReason.MISSING_PREREQUISITE, "page_checks knows $known, not ${unknown.joinToString()}")
            }
            PageShare.problem(args)?.let { throw RunFailure(FailureReason.MISSING_PREREQUISITE, "page_checks: $it") }
            val jobs = PageShare.jobs(list(args["pages"]).ifEmpty { listOf("home") }, args, step.share)
            val maxOutbound = args["max_outbound"]?.toIntOrNull() ?: DEFAULT_MAX_OUTBOUND
            val problems = mutableListOf<String>()
            val titles = LinkedHashMap<String, MutableSet<String>>()
            val outbound = LinkedHashMap<String, String>()
            val screens = Screens(this)
            var firstFailing: PageShare.Job? = null
            try {
                for (job in jobs) {
                    screens.show(job.device)
                    val ref = job.page
                    val path = runtime.target.resolvePath(ref)
                    val where = job.where(path)
                    val before = problems.size
                    open(ref)
                    val facts = act("read what a visitor sees on $path") { runtime.session.pageFacts() }
                    if (facts == null) {
                        note("page facts of $where", StepStatus.SKIPPED, "the browser could not read the page")
                        continue
                    }
                    if ("anchors" in checks) {
                        facts.missingAnchors.forEach { problems += "$where links to $it, which names no element on the page" }
                    }
                    if ("images" in checks) {
                        facts.images
                            .filterNot { it.loaded }
                            .take(MAX_LISTED)
                            .forEach { problems += "$where: the image ${it.src} does not load" }
                    }
                    if ("alt" in checks) altProblem(where, facts)?.let { problems += it }
                    if ("meta" in checks) {
                        problems += metaProblems(where, facts)
                        facts.title
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { titles.getOrPut(it) { linkedSetOf() } += path }
                    }
                    if ("outbound" in checks && job.asksLinks) {
                        val here = hostOf(runtime.session.currentUrl())
                        facts.links
                            .filter { external(it.url, here) }
                            .forEach { outbound.putIfAbsent(withoutFragment(it.url), path) }
                    }
                    if (problems.size > before && firstFailing == null) firstFailing = job
                }
                if ("meta" in checks) {
                    titles.filterValues { it.size > 1 }.forEach { (title, shared) ->
                        problems += "${shared.joinToString()} share the title \"$title\""
                    }
                }
                val unverified = mutableListOf<String>()
                if ("outbound" in checks) {
                    PageShare.links(outbound.entries.take(maxOutbound), args, step.share).forEach { (url, page) ->
                        val status =
                            probe("check the link to $url", {
                                try {
                                    runtime.session.request("GET", url, null).status
                                } catch (_: BrowserActionException) {
                                    NO_ANSWER
                                }
                            }) { it != NO_ANSWER && it !in DEAD }
                        when {
                            status == NO_ANSWER -> problems += "$page links to $url, which does not answer"
                            status in DEAD -> problems += "$page links to $url, which answers $status"
                            status >= REFUSED_FROM -> unverified += "$url ($status)"
                        }
                    }
                }
                val skipped =
                    if (unverified.isEmpty()) "" else " ${unverified.size} link(s) to sites that refuse automated visitors were not judged."
                if (problems.isEmpty()) {
                    succeeded("Checked ${jobs.size} page(s) for ${checks.sorted().joinToString()}: nothing wrong.$skipped")
                } else {
                    firstFailing?.takeIf { it != jobs.last() }?.let { job ->
                        screens.show(job.device)
                        open(job.page)
                    }
                    captureScreenshot()
                    note("problems found", StepStatus.FAILED, problems.joinToString("; "))
                    val listed = problems.take(MAX_LISTED).joinToString("; ")
                    failed(FailureReason.UNHEALTHY_PAGE, "${problems.size} problem(s): $listed$skipped")
                }
            } finally {
                screens.restore()
            }
        }

    private fun altProblem(
        path: String,
        facts: PageFacts,
    ): String? {
        val missing = facts.images.filter { it.alt == null }
        if (missing.isEmpty()) return null
        return "$path: ${missing.size} image(s) without alt text (" + missing.take(ALT_EXAMPLES).joinToString { it.src } + ")"
    }

    private fun metaProblems(
        path: String,
        facts: PageFacts,
    ): List<String> =
        listOfNotNull(
            "$path has no title".takeIf { facts.title.isBlank() },
            "$path has no main heading (h1)".takeIf { facts.headings.isEmpty() },
            "$path has ${facts.headings.size} main headings (h1)".takeIf { facts.headings.size > 1 },
            "$path has no description (meta description)".takeIf { facts.description.isNullOrBlank() },
            "$path does not name its language (html lang)".takeIf { facts.language.isNullOrBlank() },
        )

    /** A web address on another host than [here], the host of the page the link is on. */
    private fun external(
        url: String,
        here: String?,
    ): Boolean {
        val address = runCatching { URI(url) }.getOrNull() ?: return false
        val host = address.host?.lowercase() ?: return false
        return address.scheme?.lowercase() in WEB_SCHEMES && host != here
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun withoutFragment(url: String): String = url.substringBefore('#')

    private fun list(text: String?): List<String> =
        text
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        val ALL_CHECKS: List<String> = listOf("anchors", "images", "alt", "meta", "outbound")
        const val DEFAULT_MAX_OUTBOUND = 40
        private const val NO_ANSWER = -1
        private const val REFUSED_FROM = 400
        private val DEAD = setOf(404, 410)
        private val WEB_SCHEMES = setOf("http", "https")
        private const val MAX_LISTED = 10
        private const val ALT_EXAMPLES = 3
    }
}
