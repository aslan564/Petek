package az.petek.app.panel.explorer

import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.ExplorationEventView
import az.petek.dashboard.domain.ExplorationFindingView
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.PageNodeView
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PhaseProgress
import az.petek.dashboard.domain.PhaseState
import az.petek.dashboard.domain.SiteModelView
import az.petek.dashboard.domain.TestIdeaView
import az.petek.dashboard.domain.UnknownView
import az.petek.dashboard.domain.VisitedPageView
import az.petek.explorer.domain.ActionModel
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.ExplorationSummary
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.Unknown
import az.petek.explorer.domain.UrlPatterns
import java.net.URI
import java.time.Instant
import az.petek.dashboard.domain.ExplorationPhase as ViewPhase
import az.petek.dashboard.domain.ExplorationStatus as ViewStatus
import az.petek.dashboard.domain.Provenance as ViewProvenance

/**
 * One exploration as the "Kəşfiyyat" screen shows it, folded from the explorer's events as they arrive (or as they are
 * replayed from the stored log after a restart) plus what the panel adds around them: preparing role sessions,
 * the owner's answers, the final site model with its test ideas and the draft preview. The view's page budget is the
 * total over the crawl passes known so far, since the explorer's own budget bounds each pass.
 *
 * While the exploration runs, the site model is built from the events (pages visited, actions discovered); when it
 * ends, [finished] replaces it with the stored model, which also has forms, purposes and real-time channels. Lists the
 * screen shows newest first ([ExplorationView.visited], [ExplorationView.activity]) are bounded.
 *
 * Not thread-safe: the adapter serializes every call.
 *
 * @param startedAt when the owner started it (the elapsed time includes preparing role sessions).
 * @param instructions the owner's instructions with the answers given so far, as the explorer is grounded by them.
 */
internal class ExplorationTracker(
    private val target: String,
    private var instructions: String,
    private val budget: PanelBudget,
    private val startedAt: HarnessTimestamp,
    private val previousModelVersion: Int?,
) {
    private var id: ExplorationId? = null
    private var status = ViewStatus.RUNNING
    private var endedMs: Long? = null
    private var message: String? = null
    private val phases = ExplorationPhase.entries.associateWithTo(LinkedHashMap()) { PhaseTrack() }
    private var runningPhase: ExplorationPhase? = null
    private val visited = ArrayDeque<VisitedPageView>()
    private val pages = LinkedHashMap<String, LivePage>()
    private val actions = LinkedHashMap<String, ActionModel>()
    private val findings = ArrayList<ExplorationFindingView>()
    private val unknowns = LinkedHashMap<String, Unknown>()
    private val answers = HashMap<String, String>()
    private val activity = ArrayDeque<ExplorationEventView>()
    private var model: SiteModel? = null
    private var ideas: List<TestIdeaView> = emptyList()
    private var draftYaml: String? = null
    private var liveIdeasStale = true

    /** The explorer's id once it started (null while role sessions are prepared). */
    val explorationId: ExplorationId? get() = id

    val running: Boolean get() = status == ViewStatus.RUNNING

    val grounding: String get() = instructions

    /** The explorer's question [unknownId], or null when this exploration raised none with that id. */
    fun unknown(unknownId: String): Unknown? = unknowns[unknownId]

    fun unknowns(): List<Unknown> = unknowns.values.toList()

    // --- events ---------------------------------------------------------------------------------------------------

    fun apply(event: ExplorationEvent) {
        val at = event.header.at
        when (event) {
            is ExplorationEvent.Started -> {
                id = event.explorationId
                log(
                    at,
                    STARTED,
                    "Kəşfiyyat başladı: ${URI(target).host ?: target} · ${event.phases.joinToString { ExplorerTexts.phase(it) }}",
                )
            }

            is ExplorationEvent.PhaseStarted -> {
                finishRunningPhase()
                runningPhase = event.phase
                phases.getValue(event.phase).apply {
                    state = PhaseState.RUNNING
                    roles = event.roles
                }
                val roles = event.roles.joinToString { ExplorerTexts.role(it) }
                log(at, PHASE_STARTED, "${ExplorerTexts.phase(event.phase)} başladı" + if (roles.isEmpty()) "" else " · $roles")
            }

            is ExplorationEvent.PhaseSkipped -> {
                phases.getValue(event.phase).state = PhaseState.SKIPPED
                log(at, PHASE_SKIPPED, "${ExplorerTexts.phase(event.phase)} buraxıldı: ${ExplorerTexts.skipReason(event.reason)}")
            }

            is ExplorationEvent.PageVisited -> {
                pageVisited(event)
            }

            is ExplorationEvent.ActionDiscovered -> {
                actions[event.action.id] = event.action
                liveIdeasStale = true
                val page = pages.values.firstOrNull { it.id == event.action.pageId }?.pattern ?: event.action.pageId
                log(at, ACTION_DISCOVERED, "${event.action.name} (${ExplorerTexts.kind(event.action.kind)}) · $page")
            }

            is ExplorationEvent.FindingRecorded -> {
                if (findings.size < FINDINGS_LIMIT) findings += ExplorerViews.finding(event.finding)
                log(at, FINDING_RECORDED, "${ExplorerTexts.finding(event.finding.kind)}: ${event.finding.detail}")
            }

            is ExplorationEvent.UnknownRaised -> {
                unknowns[event.unknown.id] = event.unknown
                log(at, UNKNOWN_RAISED, event.unknown.question)
            }

            // The counts are shown from the pages and actions themselves.
            is ExplorationEvent.ModelUpdated -> {}

            is ExplorationEvent.DraftReady -> {
                log(
                    at,
                    DRAFT_READY,
                    "Ssenari layihəsi hazırdır: ${event.name} (${event.covered} ideya əhatə olunub, ${event.skipped} buraxılıb)",
                )
            }

            is ExplorationEvent.Finished -> {
                ended(event.summary, at)
                event.summary.notes.forEach { log(at, NOTE, ExplorerTexts.note(it)) }
                log(at, FINISHED, finishedLine(event.summary))
            }

            is ExplorationEvent.Failed -> {
                finishRunningPhase()
                status = ViewStatus.FAILED
                endedMs = endedMs ?: elapsedAt(at)
                message = "Kəşfiyyat xəta ilə dayandı: ${event.reason}. Öyrənilənlər saxlanıldı."
                log(at, FAILED, event.reason)
            }
        }
    }

    /** A line of the panel's own (preparing role sessions, draft preview problems). */
    fun note(
        at: Instant,
        kind: String,
        text: String,
    ) = log(at, kind, text)

    /** Records the owner's [answer] to [unknownId]; [grounding] is the instructions with it appended. */
    fun answered(
        unknownId: String,
        answer: String,
        grounding: String,
    ) {
        answers[unknownId] = answer
        instructions = grounding
    }

    /** The stored model of the finished exploration, its test ideas and a draft preview (null when none could be made). */
    fun finished(
        model: SiteModel?,
        ideas: List<TestIdeaView>,
        draftYaml: String?,
    ) {
        this.model = model
        this.ideas = ideas
        this.draftYaml = draftYaml
        liveIdeasStale = false
    }

    /** The draft stored for the owner (after "Ssenari yarat"); it replaces the preview. */
    fun drafted(yaml: String) {
        draftYaml = yaml
    }

    /** The exploration could not start or broke outside the explorer (browser, role sessions). */
    fun failed(
        at: HarnessTimestamp,
        reason: String,
    ) {
        if (status != ViewStatus.RUNNING) return
        finishRunningPhase()
        status = ViewStatus.FAILED
        endedMs = startedAt.elapsedUntil(at).inWholeMilliseconds
        message = "Kəşfiyyat alınmadı: $reason"
        log(at.wall, FAILED, reason)
    }

    /** Stopped by the owner before the explorer itself started (while role sessions were prepared). */
    fun cancelled(at: HarnessTimestamp) {
        if (status != ViewStatus.RUNNING) return
        finishRunningPhase()
        status = ViewStatus.CANCELLED
        endedMs = startedAt.elapsedUntil(at).inWholeMilliseconds
        message = "Dayandırıldı."
        log(at.wall, FINISHED, "Kəşfiyyat dayandırıldı.")
    }

    /**
     * A replayed exploration whose process died while it ran: nothing will finish it, so it is shown as failed with
     * whatever its events and stored records say.
     */
    fun interrupted(endedAt: Instant?) {
        if (status != ViewStatus.RUNNING) return
        finishRunningPhase()
        status = ViewStatus.FAILED
        endedMs = endedAt?.let { it.toEpochMilli() - startedAt.wall.toEpochMilli() }?.coerceAtLeast(0)
        message = "Kəşfiyyat yarımçıq qalıb: Pətək işləyərkən dayandırılıb. Öyrənilənlər saxlanılıb."
    }

    // --- view -----------------------------------------------------------------------------------------------------

    fun view(now: HarnessTimestamp): ExplorationView {
        val finalModel = model
        return ExplorationView(
            id = id?.value ?: PREPARING_ID,
            target = target,
            instructions = instructions,
            status = status,
            startedAt = startedAt.wall,
            elapsedMs = endedMs ?: startedAt.elapsedUntil(now).inWholeMilliseconds.coerceAtLeast(0),
            budget = budget.copy(maxPages = budget.maxPages * crawlPasses()),
            phases =
                phases.map { (phase, track) ->
                    PhaseProgress(ViewPhase.valueOf(phase.name), track.state, track.pages, track.roles)
                },
            currentPage = visited.firstOrNull(),
            visited = visited.toList(),
            model = finalModel?.let(ExplorerViews::model) ?: liveModel(),
            findings = findings.toList(),
            unknowns = unknowns.values.map { UnknownView(it.id, it.question, it.context, answers[it.id]) },
            ideas = if (finalModel != null) ideas else liveIdeas(),
            draftYaml = draftYaml,
            previousModelVersion = previousModelVersion,
            activity = activity.toList(),
            message = message,
        )
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun pageVisited(event: ExplorationEvent.PageVisited) {
        runningPhase?.let { phases.getValue(it).pages++ }
        visited.addFirst(
            VisitedPageView(
                url = event.url,
                title = event.title,
                visitedAs = event.role,
                httpStatus = event.status,
                loadMs = event.loadMs,
                screenshotArtifactId = event.screenshotArtifactId,
                at = event.header.at,
            ),
        )
        while (visited.size > VISITED_LIMIT) visited.removeLast()
        val page = pages.getOrPut(event.urlPattern) { LivePage(event.urlPattern, UrlPatterns.pageId(event.urlPattern)) }
        if (page.title.isBlank()) page.title = event.title
        page.reachableBy += event.role
        liveIdeasStale = true
        val status = event.status?.let { " · HTTP $it" }.orEmpty()
        log(event.header.at, PAGE_VISITED, "${event.urlPattern} · ${ExplorerTexts.role(event.role)}$status")
    }

    /** The elapsed time is the owner's: from the start (preparing role sessions included) to the end event. */
    private fun ended(
        summary: ExplorationSummary,
        at: Instant,
    ) {
        finishRunningPhase()
        status =
            when (summary.status) {
                ExplorationStatus.RUNNING, ExplorationStatus.COMPLETED -> ViewStatus.FINISHED
                ExplorationStatus.TIMED_OUT -> ViewStatus.TIMED_OUT
                ExplorationStatus.CANCELLED -> ViewStatus.CANCELLED
                ExplorationStatus.FAILED -> ViewStatus.FAILED
            }
        endedMs = maxOf(summary.durationMs, elapsedAt(at))
        message =
            when {
                summary.status == ExplorationStatus.TIMED_OUT -> "Vaxt büdcəsi bitdi; öyrənilənlər saxlanıldı."
                summary.status == ExplorationStatus.CANCELLED -> "Dayandırıldı; öyrənilənlər saxlanıldı."
                summary.pageBudgetReached -> "Səhifə limiti (${budget.maxPages}) doldu; bəzi səhifələrə baxılmadı."
                else -> null
            }
    }

    private fun finishedLine(summary: ExplorationSummary): String {
        val counts = summary.counts
        val facts = "${counts.pages} səhifə, ${counts.actions} əməliyyat, ${counts.forms} form, ${counts.findings} tapıntı"
        return when (summary.status) {
            ExplorationStatus.TIMED_OUT -> "Vaxt bitdi: $facts"
            ExplorationStatus.CANCELLED -> "Dayandırıldı: $facts"
            ExplorationStatus.FAILED -> "Xəta ilə bitdi: $facts"
            else -> "Kəşfiyyat bitdi: $facts"
        }
    }

    private fun finishRunningPhase() {
        runningPhase?.let { phase -> phases.getValue(phase).takeIf { it.state == PhaseState.RUNNING }?.state = PhaseState.DONE }
        runningPhase = null
    }

    /**
     * How many crawl passes the page budget applies to: the explorer bounds each pass separately (the anonymous one and
     * one per role of the role-based walk; the trial touch visits no pages), so the screen's total is per pass times
     * passes. Known passes only: it grows when the role-based walk starts.
     */
    private fun crawlPasses(): Int = maxOf(1, CRAWLING_PHASES.sumOf { phases.getValue(it).roles.size })

    private fun elapsedAt(at: Instant): Long = (at.toEpochMilli() - startedAt.wall.toEpochMilli()).coerceAtLeast(0)

    private fun log(
        at: Instant,
        kind: String,
        text: String,
    ) {
        activity.addFirst(ExplorationEventView(at, kind, text))
        while (activity.size > ACTIVITY_LIMIT) activity.removeLast()
    }

    private fun liveModel(): SiteModelView =
        SiteModelView(
            version = (previousModelVersion ?: 0) + 1,
            pages =
                pages.values.map { page ->
                    PageNodeView(
                        id = page.id,
                        urlPattern = page.pattern,
                        title = page.title,
                        purpose = "",
                        reachableBy = page.reachableBy.sorted(),
                        provenance = ViewProvenance.OBSERVED,
                        forms = emptyList(),
                        actions = actions.values.filter { it.pageId == page.id }.map(ExplorerViews::action),
                    )
                },
            realtime = emptyList(),
        )

    private var cachedIdeas: List<TestIdeaView> = emptyList()

    /** Ideas from what was seen so far; recomputed only when a page or an action was added. */
    private fun liveIdeas(): List<TestIdeaView> {
        val exploration = id ?: return emptyList()
        if (!liveIdeasStale) return cachedIdeas
        val partial =
            SiteModel(
                version = (previousModelVersion ?: 0) + 1,
                explorationId = exploration,
                target = URI(target),
                createdAt = startedAt.wall,
                pages = pages.values.map(LivePage::model),
                actions = actions.values.toList(),
                roles = emptyList(),
                realtime = emptyList(),
                unknowns = emptyList(),
                partial = true,
            )
        cachedIdeas = ExplorerViews.ideas(partial, instructions)
        liveIdeasStale = false
        return cachedIdeas
    }

    private class PhaseTrack(
        var state: PhaseState = PhaseState.PENDING,
        var pages: Int = 0,
        var roles: List<String> = emptyList(),
    )

    private class LivePage(
        val pattern: String,
        val id: String,
        var title: String = "",
        val reachableBy: MutableSet<String> = LinkedHashSet(),
    ) {
        fun model(): PageModel =
            PageModel(
                id = id,
                urlPattern = pattern,
                title = title,
                purpose = "",
                reachableBy = reachableBy.toSet(),
                forms = emptyList(),
                testIds = emptyList(),
                linkCount = 0,
                loadMs = null,
                provenance = Provenance.OBSERVED,
                evidence = emptyList(),
            )
    }

    companion object {
        /** The view's id while role sessions are prepared, before the explorer named the exploration. */
        const val PREPARING_ID = "hazırlanır"

        const val STARTED = "STARTED"
        const val PHASE_STARTED = "PHASE_STARTED"
        const val PHASE_SKIPPED = "PHASE_SKIPPED"
        const val PAGE_VISITED = "PAGE_VISITED"
        const val ACTION_DISCOVERED = "ACTION_DISCOVERED"
        const val FINDING_RECORDED = "FINDING_RECORDED"
        const val UNKNOWN_RAISED = "UNKNOWN_RAISED"
        const val DRAFT_READY = "DRAFT_READY"
        const val FINISHED = "FINISHED"
        const val FAILED = "FAILED"
        const val NOTE = "NOTE"
        const val SESSIONS = "SESSIONS"

        private val CRAWLING_PHASES = listOf(ExplorationPhase.ANONYMOUS, ExplorationPhase.ROLE_BASED)

        const val VISITED_LIMIT = 500
        const val ACTIVITY_LIMIT = 300
        const val FINDINGS_LIMIT = 500
    }
}
