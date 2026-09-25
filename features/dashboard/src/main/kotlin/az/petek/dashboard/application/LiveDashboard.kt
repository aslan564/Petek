package az.petek.dashboard.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.AgentDetail
import az.petek.dashboard.domain.DashboardSnapshot
import az.petek.dashboard.domain.DashboardState
import az.petek.dashboard.domain.DashboardUpdate
import az.petek.dashboard.domain.OrchestratorSnapshot
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.TaskStateView
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.RunRecord
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/**
 * The live dashboard's model: "who is doing what, right now" (docs/ARCHITECTURE.md, `features/dashboard`).
 *
 * It listens as a [MonitorView] (agent states, scenario steps, messages, the run's end) and is fed recorded evidence
 * by [DashboardEvidenceRecorder]; [DashboardRunRepository] and [DashboardIdentityRepository] add the campaign name,
 * target, departments and registration modes. All of it is folded by the pure [DashboardState] reducer.
 *
 * Threading: every entry point may be called from any thread or coroutine and never waits: the caller computes the
 * next immutable state and swaps it in with a compare-and-set (retrying if another caller won), nothing else. An
 * update that fails is logged and dropped, so observing a run can never break it. [snapshot] always reflects every
 * update that returned before it was called.
 *
 * [updates] emits the current snapshot to each new collector at once, then at most one snapshot per
 * [refreshInterval] (4 per second by default), always the latest; nothing is emitted while nothing changes.
 * [orchestratorUpdates] does the same for the orchestrator screen, and only when its part changed.
 *
 * App integration contract (the composition root, `app`):
 * 1. Create one `LiveDashboard(clock)` per process.
 * 2. Wrap the evidence recorder with [DashboardEvidenceRecorder] as the outermost decorator, and hand the wrapped
 *    recorder to everything that records (runner, agent loop, run functions, verification, finalizer).
 * 3. Pass the dashboard to the runner as a [MonitorView], in a `CompositeMonitorView` with the console board, and let
 *    the orchestrator report its plan ([planReady]) and every task change ([taskUpdated]). Events and receipts arrive
 *    through the recorder; [eventPublished] and [eventReceived] exist for callers that do not record them (duplicates
 *    count once).
 * 4. Give the runner `DashboardRunRepository(runs, dashboard)` and `DashboardIdentityRepository(identities, dashboard)`
 *    (optional: without them the header lacks campaign and target, and cards lack department and registration).
 * 5. Start `DashboardServer(dashboard, artifacts, reportDirectory)` before the run, print its URL and open it in the
 *    browser; after the run keep serving until the user presses Enter, then close the server.
 * 6. `petek dashboard <run_id>`: load the run, its identities and evidence with [replay] and serve it the same way.
 */
class LiveDashboard(
    private val clock: HarnessClock,
    private val refreshInterval: Duration = 250.milliseconds,
) : MonitorView {
    init {
        require(refreshInterval.isPositive()) { "refreshInterval must be positive, was $refreshInterval" }
    }

    private val state = MutableStateFlow(DashboardState.EMPTY)
    private val artifacts = ArtifactIndex()
    private val failures = AtomicLong()

    /** The run on the board, or null before any run was seen. */
    val currentRunId: RunId? get() = state.value.runId

    /** Directory of the shown run's report, once the run has ended with one. */
    val reportPath: String? get() = state.value.reportPath

    fun snapshot(): DashboardSnapshot = state.value.snapshot(clock.now())

    /** Throttled live snapshots (see the class comment). Cold: every collector gets its own pace. */
    val updates: Flow<DashboardSnapshot> = throttled({ it.version }) { current, now -> current.snapshot(now) }

    /** The orchestrator screen's view: plan, task matrix and events of the run on the board. */
    fun orchestrator(): OrchestratorSnapshot = state.value.orchestrator(clock.now())

    /** Throttled like [updates], emitted only when the plan, a task, an event or the run header changed. */
    val orchestratorUpdates: Flow<OrchestratorSnapshot> =
        throttled({ it.orchestratorVersion }) { current, now -> current.orchestrator(now) }

    /** The agent's card with its own recent timeline; null for an agent not on the board. */
    fun agentDetail(agentId: AgentId): AgentDetail? = state.value.agentDetail(agentId)

    /** A recorded artifact of the run on the board; null for unknown ids and for artifacts of any other run. */
    fun artifact(artifactId: ArtifactId): ArtifactRecord? = currentRunId?.let { artifacts.byId(it, artifactId) }

    /**
     * A recorded artifact of the run on the board by its path inside the run directory (`a07/0003-screenshot.png`),
     * the form in which the written report links its evidence. Only paths of recorded artifacts resolve.
     */
    fun artifactAt(pathInRun: String): ArtifactRecord? = currentRunId?.let { artifacts.byPathInRun(it, pathInRun) }

    // --- MonitorView ----------------------------------------------------------------------------------------------

    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) = submit { DashboardUpdate.RunStarted(runId, agents, it) }

    override fun agentUpdated(status: AgentStatus) = submit { DashboardUpdate.AgentUpdated(status, it) }

    override fun stepStarted(scenarioStep: String) = submit { DashboardUpdate.ScenarioStepStarted(scenarioStep, it) }

    override fun message(text: String) = submit { DashboardUpdate.Message(text, it) }

    override fun runFinished(summary: RunSummary) =
        submit { DashboardUpdate.RunEnded(summary.runId, summary.outcome, summary.durationMs, summary.reportDirectory, it) }

    // --- orchestrator ---------------------------------------------------------------------------------------------

    /** The orchestrator resolved its plan: steps in order with their agents, emits and wait_for. */
    fun planReady(plan: RunPlanView) = submit { DashboardUpdate.PlanReady(plan, it) }

    /** One task (step × agent) changed state. */
    fun taskUpdated(task: TaskStateView) = submit { DashboardUpdate.TaskUpdated(task, it) }

    fun eventPublished(record: EventRecord) = submit { DashboardUpdate.EventRecorded(record, it) }

    fun eventReceived(receipt: EventReceipt) = submit { DashboardUpdate.ReceiptRecorded(receipt, it) }

    // --- replay ---------------------------------------------------------------------------------------------------

    /**
     * Puts a finished (or interrupted) run on the board from the evidence store, with its artifacts, replacing
     * whatever was shown. Returns the resulting snapshot.
     */
    suspend fun replay(
        run: RunRecord,
        query: EvidenceQuery,
        identities: List<Identity>,
        reportPath: String? = null,
    ): DashboardSnapshot {
        val replay = EvidenceReplay.load(run, query, identities, reportPath)
        state.update { old -> old.cleared().applyAll(replay.updates) }
        artifacts.replaceWith(run.runId, replay.artifacts)
        return snapshot()
    }

    // --- feeding --------------------------------------------------------------------------------------------------

    /** At once for a new collector, then at most once per [refreshInterval] and only when [key] moved; always the latest. */
    private fun <T> throttled(
        key: (DashboardState) -> Long,
        view: (DashboardState, HarnessTimestamp) -> T,
    ): Flow<T> =
        flow {
            var seen: Long? = null
            while (true) {
                val current = state.first { key(it) != seen }
                seen = key(current)
                emit(view(current, clock.now()))
                delay(refreshInterval)
            }
        }

    /** Applies the update [build] makes for the harness's current time; never throws, never waits. */
    internal fun submit(build: (HarnessTimestamp) -> DashboardUpdate) {
        try {
            val update = build(clock.now())
            if (update is DashboardUpdate.ArtifactRecorded) artifacts.add(update.record)
            state.update { it.apply(update) }
            state.value.runId?.let(artifacts::focus)
        } catch (e: Exception) {
            val count = failures.incrementAndGet()
            if (count == 1L) {
                logger.warn(e) { "the dashboard could not apply an update; the run continues" }
            } else {
                logger.debug(e) { "the dashboard could not apply an update ($count so far)" }
            }
        }
    }
}
