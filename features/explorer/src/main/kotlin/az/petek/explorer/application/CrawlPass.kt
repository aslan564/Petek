package az.petek.explorer.application

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.RealtimeTransport
import az.petek.core.ids.ArtifactId
import az.petek.explorer.domain.ActionCandidate
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.domain.Keywords
import az.petek.explorer.domain.LinkPolicy
import az.petek.explorer.domain.LinkVerdict
import az.petek.explorer.domain.PageAnalysis
import az.petek.explorer.domain.PageFacts
import az.petek.explorer.domain.PageHeuristics
import az.petek.explorer.domain.PageObservation
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.RobotsRules
import az.petek.explorer.domain.ScannedDocument
import az.petek.explorer.domain.Selectors
import az.petek.explorer.domain.Severity
import az.petek.explorer.domain.SkipReason
import az.petek.explorer.domain.UrlPatterns
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.util.PriorityQueue

private val logger = KotlinLogging.logger {}

/**
 * One crawl of the target from one viewpoint (`anonymous` or a role), breadth-first within the budget:
 *
 * - The queue is ordered by how well a link matches the owner's instructions, then by depth, then by discovery, so
 *   matching links are opened first and the rest level by level. The target is depth 0; well-known sign-in and
 *   sign-up paths are tried at depth 1 when they answer.
 * - One page per URL pattern: `/tickets/t1` and `/tickets/t2` are one kind of page, visited once per viewpoint.
 * - Every address is checked with a GET first (no redirects followed): 401/403 mark the page as refused for this
 *   viewpoint, 404/410 are broken links, 5xx and other 4xx are HTTP errors; only then is the page opened.
 * - The session is a [ReadOnlyBrowserSession]: the pass can look, never act, and never leave the origin; links are
 *   also filtered by [az.petek.explorer.domain.LinkPolicy] (logout/delete-looking links, robots.txt, APIs, files).
 * - Per page: evidence, code heuristics, one LLM question, findings (slow, accessibility, leaked errors), live-update
 *   transports, then the page's links.
 */
internal class CrawlPass(
    private val context: ExplorationContext,
    private val role: String,
    private val anonymous: Boolean,
    private val session: BrowserSession,
) {
    private data class QueuedLink(
        val url: URI,
        val depth: Int,
        val score: Int,
        val order: Long,
        val guessed: Boolean,
    )

    private val frontier =
        PriorityQueue(compareByDescending<QueuedLink> { it.score }.thenBy { it.depth }.thenBy { it.order })
    private val queuedPatterns = HashSet<String>()
    private val visitedPatterns = HashSet<String>()
    private val probes = HashMap<URI, HttpProbeResult?>()
    private val skipped = LinkedHashMap<SkipReason, Int>()
    private val seenRealtimeDetails = HashSet<String>()
    private val seenTransports = HashSet<RealtimeTransport>()
    private var linkChecks = 0
    private var visited = 0
    private var order = 0L
    private val budget = context.request.budget
    private val viewer = if (anonymous) "an anonymous visitor (not logged in)" else "a logged-in user with the role '$role'"

    suspend fun run() {
        context.accumulator.role(role, anonymous)
        checkRobots()
        val target = context.request.target
        enqueue(target, depth = 0, text = "", guessed = false)
        // Sign-in and sign-up pages only matter to visitors; logged-in users are sent away from them anyway.
        if (anonymous) seedGuesses(target)
        while (frontier.isNotEmpty()) {
            // Between pages: a cancelled exploration (e.g. stopped from the panel) ends here, not after the whole pass.
            currentCoroutineContext().ensureActive()
            if (context.deadlinePassed()) break
            if (visited >= budget.maxPages) {
                context.pageBudgetReached = context.pageBudgetReached || frontier.any { UrlPatterns.of(it.url) !in visitedPatterns }
                break
            }
            visit(frontier.poll())
        }
        if (skipped.isNotEmpty()) {
            context.notes += "$role: links not followed: " + skipped.entries.joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }
        }
    }

    private suspend fun checkRobots() {
        if (context.robotsChecked || !context.settings.respectRobotsTxt) return
        context.robotsChecked = true
        val answer = probe(context.request.target.resolve("/robots.txt")) ?: return
        if (answer.status != 200) return
        val rules = RobotsRules.parse(answer.body)
        context.policy = LinkPolicy(context.origin, rules)
        if (rules.disallowCount > 0) {
            context.notes += "robots.txt keeps ${rules.disallowCount} path rule(s) out of the exploration"
        }
    }

    private suspend fun seedGuesses(target: URI) {
        for (path in context.settings.seedPaths) {
            val url = UrlPatterns.resolve(target, path) ?: continue
            if (context.policy.verdict(url) != LinkVerdict.Follow) continue
            if (UrlPatterns.of(url) in queuedPatterns) continue
            val status = probe(url)?.status ?: continue
            if (status in 200..399) enqueue(url, depth = 1, text = path, guessed = true)
        }
    }

    private fun enqueue(
        url: URI,
        depth: Int,
        text: String,
        guessed: Boolean,
    ) {
        val pattern = UrlPatterns.of(url)
        if (pattern in visitedPatterns || !queuedPatterns.add(pattern)) return
        val score = Keywords.score(context.keywords, "$text ${url.rawPath.orEmpty()}")
        frontier += QueuedLink(url, depth, score, order++, guessed)
    }

    private suspend fun visit(link: QueuedLink) {
        val pattern = UrlPatterns.of(link.url)
        if (!visitedPatterns.add(pattern)) return
        val answer = probe(link.url)
        if (answer != null && refusedBeforeLoading(link, pattern, answer)) return
        val loadMs =
            try {
                context.capture.load(session, link.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BrowserActionException) {
                context.findings.record(
                    FindingKind.HTTP_ERROR,
                    Severity.MEDIUM,
                    UrlPatterns.display(link.url),
                    "The page did not load: ${e.message}",
                    role,
                    emptyList(),
                )
                return
            }
        val finalUrl = currentUrl() ?: link.url
        if (!context.origin.contains(finalUrl)) {
            context.raiseUnknown(
                "Opening $pattern sends the browser to another site (${finalUrl.host}). Is that expected?",
                "Seen as $role; the explorer does not follow other sites.",
                null,
                Provenance.OBSERVED,
                emptyList(),
            )
            return
        }
        val finalPattern = UrlPatterns.of(finalUrl)
        if (finalPattern != pattern) {
            if (looksLikeSignIn(finalPattern)) context.accumulator.recordDenied(role, pattern)
            if (!visitedPatterns.add(finalPattern)) return
        }
        visited++
        context.pagesVisitedByRole.merge(role, 1, Int::plus)
        try {
            learn(finalUrl, finalPattern, link.depth, answer?.status, loadMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrowserActionException) {
            // One page that cannot be read (it navigated away mid-snapshot, the tab crashed) must not end the exploration.
            logger.warn { "Reading $finalPattern as $role failed: ${e.message}" }
            context.notes += "$role: $finalPattern could not be read: ${e.message}"
        }
    }

    /** Handles an error status from the pre-check; true when the page is not opened. */
    private suspend fun refusedBeforeLoading(
        link: QueuedLink,
        pattern: String,
        answer: HttpProbeResult,
    ): Boolean {
        val status = answer.status
        when {
            status == 401 || status == 403 -> {
                context.accumulator.recordDenied(role, pattern)
            }

            status == 404 || status == 410 -> {
                if (!link.guessed) brokenLink(link.url, "", null, answer)
            }

            status >= 500 -> {
                httpError(link.url, answer, Severity.HIGH)
            }

            status >= 400 -> {
                httpError(link.url, answer, Severity.LOW)
            }

            else -> {
                return false
            }
        }
        return true
    }

    private suspend fun learn(
        url: URI,
        pattern: String,
        depth: Int,
        status: Int?,
        loadMs: Long,
    ) {
        val captured = context.capture.capture(session, role)
        val snapshot = captured.snapshot
        val facts = PageHeuristics.inspect(snapshot, captured.document, url)
        val analysis = context.analyst.analyse(snapshot, pattern, viewer, context.request.grounding, facts.forms)
        val evidence = captured.evidence
        val pageId = context.accumulator.pageIdOf(pattern)
        context.accumulator.recordPage(
            PageObservation(
                role = role,
                urlPattern = pattern,
                url = url,
                title = snapshot.title,
                purpose = analysis?.purpose,
                forms = facts.forms.map { it.copy(evidence = evidence) },
                testIds = facts.testIds,
                linkCount = facts.links.size,
                loadMs = loadMs,
                evidence = evidence,
            ),
        )
        context.emitter.emit {
            ExplorationEvent.PageVisited(it, role, UrlPatterns.display(url), pattern, snapshot.title, status, loadMs, captured.screenshot)
        }
        recordActions(pageId, facts, analysis, snapshot, captured.document, evidence)
        analysis?.unknowns?.forEach { context.raiseUnknown(it.question, it.context, pageId, Provenance.INFERRED, evidence) }
        recordFindings(url, facts, loadMs, evidence)
        observeRealtime(pageId, evidence)
        followLinks(url, pattern, depth, facts, evidence)
        context.modelUpdated()
    }

    private suspend fun recordActions(
        pageId: String,
        facts: PageFacts,
        analysis: PageAnalysis?,
        snapshot: PageSnapshot,
        document: ScannedDocument,
        evidence: List<ArtifactId>,
    ) {
        val proposed =
            analysis?.actions.orEmpty().mapNotNull { proposal ->
                candidateOf(proposal.ref, proposal.name, proposal.kind, snapshot, document)
            }
        (facts.actions + proposed).forEach { candidate ->
            val discovered = context.accumulator.recordAction(role, pageId, candidate, evidence) ?: return@forEach
            context.emitter.emit { ExplorationEvent.ActionDiscovered(it, discovered) }
        }
    }

    /** An LLM-proposed action as a candidate; plain navigation links are left to the page list. */
    private fun candidateOf(
        ref: Int,
        name: String,
        kind: ActionKind,
        snapshot: PageSnapshot,
        document: ScannedDocument,
    ): ActionCandidate? {
        val element = snapshot.elements.firstOrNull { it.ref == ref } ?: return null
        if (kind == ActionKind.NAVIGATE && element.role == "link") return null
        val selector =
            element.testId?.let(Selectors::testId)
                ?: domIdOf(document, ref)?.let(Selectors::id)
                ?: element.name.takeIf { it.isNotBlank() }?.let { Selectors.role(element.role, it) }
                ?: return null
        return ActionCandidate(name, kind, selector, null, null, Provenance.INFERRED)
    }

    /** The DOM `id` of the field or button with snapshot [ref], if it has one. */
    private fun domIdOf(
        document: ScannedDocument,
        ref: Int,
    ): String? {
        val fields = document.forms.flatMap { it.fields } + document.looseFields
        val buttons = document.forms.flatMap { it.buttons } + document.looseButtons
        return fields.firstOrNull { it.ref == ref }?.id ?: buttons.firstOrNull { it.ref == ref }?.id
    }

    private suspend fun recordFindings(
        url: URI,
        facts: PageFacts,
        loadMs: Long,
        evidence: List<ArtifactId>,
    ) {
        val page = UrlPatterns.display(url)
        val settings = context.settings
        if (loadMs >= settings.slowPageMs) {
            val severity = if (loadMs >= settings.verySlowPageMs) Severity.HIGH else Severity.MEDIUM
            context.findings.record(
                FindingKind.SLOW_PAGE,
                severity,
                page,
                "The page took $loadMs ms to load (threshold ${settings.slowPageMs} ms), measured by the harness.",
                role,
                evidence,
            )
        }
        if (facts.accessibilityIssues.isNotEmpty()) {
            val severity = if (PageHeuristics.hasUnnamedFields(facts.accessibilityIssues)) Severity.MEDIUM else Severity.LOW
            context.findings.record(FindingKind.ACCESSIBILITY, severity, page, facts.accessibilityIssues.joinToString("; "), role, evidence)
        }
        if (facts.anomalies.isNotEmpty()) {
            context.findings.record(
                FindingKind.UNEXPECTED_UI,
                Severity.MEDIUM,
                page,
                "The page shows text that looks like a leaked error: " + facts.anomalies.joinToString(" | "),
                role,
                evidence,
            )
        }
    }

    private suspend fun observeRealtime(
        pageId: String,
        evidence: List<ArtifactId>,
    ) {
        val observation =
            try {
                session.networkObservation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BrowserActionException) {
                logger.debug { "network observation failed for $role: ${e.message}" }
                return
            }
        val newDetails = observation.details.filter { seenRealtimeDetails.add(it) }
        observation.transports.forEach { transport ->
            val details = newDetails.filter { it.startsWith(DETAIL_PREFIX.getValue(transport)) }
            if (seenTransports.add(transport) || details.isNotEmpty()) {
                context.accumulator.recordRealtime(transport, details, pageId, role, evidence)
            }
        }
    }

    private suspend fun followLinks(
        page: URI,
        pattern: String,
        depth: Int,
        facts: PageFacts,
        evidence: List<ArtifactId>,
    ) {
        for (link in facts.links) {
            val url = UrlPatterns.resolve(page, link.href) ?: continue
            val verdict = context.policy.verdict(url, link.text)
            if (verdict is LinkVerdict.Skip) {
                skipped.merge(verdict.reason, 1, Int::plus)
                continue
            }
            val linkPattern = UrlPatterns.of(url)
            val known = linkPattern in visitedPatterns || linkPattern in queuedPatterns
            if (!known && depth + 1 <= budget.maxDepth) {
                enqueue(url, depth + 1, link.text, guessed = false)
            } else if (url !in probes && linkChecks < context.settings.maxLinkChecks) {
                linkChecks++
                val answer = probe(url) ?: continue
                if (answer.status == 404 || answer.status == 410) brokenLink(url, link.text, pattern, answer, evidence)
            }
        }
    }

    private suspend fun brokenLink(
        url: URI,
        text: String,
        fromPattern: String?,
        answer: HttpProbeResult,
        pageEvidence: List<ArtifactId> = emptyList(),
    ) {
        val http = context.capture.httpEvidence(role, answer.status, answer.body)
        val link = listOfNotNull("Link", text.takeIf { it.isNotBlank() }?.let { "'$it'" }, fromPattern?.let { "on $it" }).joinToString(" ")
        context.findings.record(
            FindingKind.BROKEN_LINK,
            Severity.MEDIUM,
            UrlPatterns.display(url),
            "$link points to ${UrlPatterns.display(url)}, which answers ${answer.status}.",
            role,
            pageEvidence + http,
            key = "${FindingKind.BROKEN_LINK}|${UrlPatterns.display(url)}",
        )
    }

    private suspend fun httpError(
        url: URI,
        answer: HttpProbeResult,
        severity: Severity,
    ) {
        val http = context.capture.httpEvidence(role, answer.status, answer.body)
        context.findings.record(
            FindingKind.HTTP_ERROR,
            severity,
            UrlPatterns.display(url),
            "The page answers ${answer.status}.",
            role,
            listOf(http),
        )
    }

    /** GET without following redirects, with this viewpoint's cookies; null when the request itself failed. */
    private suspend fun probe(url: URI): HttpProbeResult? {
        if (url in probes) return probes[url]
        val path = url.rawPath.orEmpty().ifEmpty { "/" } + (url.rawQuery?.let { "?$it" } ?: "")
        val answer =
            try {
                session.request("GET", path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BrowserActionException) {
                logger.debug { "GET $path as $role failed: ${e.message}" }
                null
            }
        probes[url] = answer
        return answer
    }

    private suspend fun currentUrl(): URI? =
        try {
            URI(session.currentUrl())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun looksLikeSignIn(pattern: String): Boolean = Keywords.containsStem(pattern, SIGN_IN_WORDS)

    private companion object {
        val SIGN_IN_WORDS = setOf("login", "signin", "sign", "auth", "daxil")

        /** How the browser adapter labels each transport in its details. */
        val DETAIL_PREFIX =
            mapOf(RealtimeTransport.WEBSOCKET to "WebSocket", RealtimeTransport.SSE to "SSE", RealtimeTransport.POLLING to "Polling")
    }
}
