/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.campaign.domain.FlowNames
import az.petek.core.model.RegistrationMode
import az.petek.evidence.domain.StepStatus
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/**
 * `site_health`: blind checks that need no knowledge of the site (docs/PLAN.md Faza 13), decided by code from what the
 * browser saw. For every page of `pages` (path keys or `/paths`, comma-separated; default `home`) it runs the `checks`
 * asked (comma-separated; default all):
 *
 * - `links`: every link of the page that stays on the site answers below 400 (up to `max_links`, default 30);
 * - `console`: no `console.error` or uncaught exception, and no request to the site failed (4xx/5xx or no answer);
 * - `slow`: no request to the site took `slow_ms` (default 3000) or more;
 * - `back`: after following the page's first link, the back button returns to the page;
 * - `mobile`: at `width` (default 375) × 812 the page is not wider than the screen;
 * - `session`: with the cookies gone (an expired session) the page no longer shows the signed-in user; the tester then
 *   signs in again with the `login` flow. Visitors and signed-out testers skip it.
 *
 * Every problem is listed in the outcome (`unhealthy_page`) with a screenshot; a clean site passes.
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
            val checks = list(args["checks"]).ifEmpty { ALL_CHECKS }.toSet()
            val unknown = checks - ALL_CHECKS.toSet()
            if (unknown.isNotEmpty()) {
                throw RunFailure(
                    FailureReason.MISSING_PREREQUISITE,
                    "site_health knows ${ALL_CHECKS.joinToString()}, not ${unknown.joinToString()}",
                )
            }
            val pages = list(args["pages"]).ifEmpty { listOf("home") }
            val slow = (args["slow_ms"]?.toLongOrNull() ?: DEFAULT_SLOW_MS).milliseconds
            val width = args["width"]?.toIntOrNull() ?: DEFAULT_WIDTH
            val maxLinks = args["max_links"]?.toIntOrNull() ?: DEFAULT_MAX_LINKS
            val problems = mutableListOf<String>()
            val session = runtime.session
            for (ref in pages) {
                val path = runtime.target.resolvePath(ref)
                val since = now()
                open(ref)
                val links = if ("links" in checks || "back" in checks) act("read the links of $path") { session.links() } else emptyList()
                if ("links" in checks) {
                    // Only paths on the site itself: links() never gives another origin, and a full URL is not followed.
                    links.filter { it.startsWith("/") && !it.startsWith("//") }.take(maxLinks).forEach { link ->
                        val status = probe("check link $link", { session.request("GET", link).status }) { it < BROKEN }
                        if (status >= BROKEN) problems += "$path links to $link, which answers $status"
                    }
                }
                if ("back" in checks) links.firstOrNull { pathOf(it) != path }?.let { next -> checkBack(path, next, problems) }
                if ("mobile" in checks) {
                    val overflow = act("measure $path at ${width}px") { session.horizontalOverflow(width, PHONE_HEIGHT) }
                    if (overflow != null && overflow > TOLERANCE_PX) problems += "$path is $overflow px wider than a ${width}px screen"
                }
                val health = act("read what the page reported") { session.health(since, slow) }
                if ("console" in checks) {
                    health.consoleErrors.forEach { problems += "$path: console error: $it" }
                    health.failedRequests.forEach { problems += "$path: request failed: $it" }
                }
                if ("slow" in checks) health.slowResponses.forEach { problems += "$path: ${it.describe()}" }
            }
            if ("session" in checks) checkSessionExpiry(pages.first(), problems)
            if (problems.isEmpty()) {
                succeeded("Checked ${pages.size} page(s) for ${checks.sorted().joinToString()}: nothing wrong.")
            } else {
                captureScreenshot()
                note("problems found", StepStatus.FAILED, problems.joinToString("; "))
                failed(FailureReason.UNHEALTHY_PAGE, "${problems.size} problem(s): " + problems.take(MAX_LISTED).joinToString("; "))
            }
        }

    private suspend fun RunTrace.checkBack(
        path: String,
        next: String,
        problems: MutableList<String>,
    ) {
        openUrl(next)
        val went = act("press the back button") { runtime.session.goBack() }
        val back = pathOf(currentUrl())
        if (went && back != path) problems += "the back button from $next leads to $back, not $path"
        if (!went) problems += "the back button from $next did nothing"
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

    private fun pathOf(url: String): String =
        runCatching { URI(url).rawPath }.getOrNull()?.ifEmpty { "/" }
            ?: url.substringBefore('?')

    companion object {
        val ALL_CHECKS: List<String> = listOf("links", "console", "slow", "back", "mobile", "session")
        const val DEFAULT_SLOW_MS = 3_000L
        const val DEFAULT_WIDTH = 375
        const val DEFAULT_MAX_LINKS = 30
        private const val PHONE_HEIGHT = 812
        private const val TOLERANCE_PX = 1
        private const val BROKEN = 400
        private const val MAX_LISTED = 10
    }
}
