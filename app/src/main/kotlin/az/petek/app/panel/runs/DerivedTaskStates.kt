package az.petek.app.panel.runs

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.TaskState
import az.petek.dashboard.domain.TaskStateView
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Fills the orchestrator screen's task matrix (plan step × agent) from what the panel already hears, as long as
 * orchestration does not report task states itself:
 * - an agent's status names the scenario step it is in: WAITING is [TaskState.WAITING_EVENT], WORKING is
 *   [TaskState.RUNNING], BLOCKED and FAILED are themselves, and the IDLE status that ends a step carries its result
 *   (`ok: …` passed, `<failure key>: …` failed);
 * - a SKIPPED record of an agent in a step (it failed earlier and is excluded) is [TaskState.SKIPPED];
 * - once a race's `only_one_succeeds` check passed, the step's failed actors are [TaskState.LOST_RACE].
 *
 * It is a [MonitorView] in the panel's composite and sees records through [recorder]; every derived change goes to
 * [LiveDashboard.taskUpdated]. When orchestration reports `planReady`/`taskUpdated` itself, drop this class from the
 * composite and forward those calls to the dashboard instead (a plain MonitorView adapter): the dashboard keeps the
 * latest report per cell, so nothing else changes. Thread-safe; it never throws into the run.
 */
internal class DerivedTaskStates(
    private val dashboard: LiveDashboard,
) : MonitorView {
    private val lock = Any()
    private var runId: RunId? = null
    private var plan: RunPlanView? = null
    private val cells = HashMap<Pair<String, AgentId>, TaskState>()

    /** The plan of the run about to start (from the panel's run watch); the matrix follows its steps. */
    fun planned(plan: RunPlanView) =
        synchronized(lock) {
            if (plan.runId != runId) cells.clear()
            runId = plan.runId
            this.plan = plan
        }

    /** A decorator that shows [delegate]'s skipped steps and race checks in the matrix; wrap it inside the dashboard's. */
    fun recorder(delegate: EvidenceRecorder): EvidenceRecorder =
        object : EvidenceRecorder by delegate {
            override suspend fun step(record: StepRecord) {
                delegate.step(record)
                safely { stepRecorded(record) }
            }

            override suspend fun assertion(record: AssertionRecord) {
                delegate.assertion(record)
                safely { assertionRecorded(record) }
            }
        }

    // --- MonitorView ----------------------------------------------------------------------------------------------

    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) = synchronized(lock) {
        if (this.runId != runId) {
            this.runId = runId
            plan = null
            cells.clear()
        }
    }

    override fun agentUpdated(status: AgentStatus) =
        safely {
            val step = status.scenarioStep ?: return@safely
            val state =
                when (status.state) {
                    AgentState.WAITING -> TaskState.WAITING_EVENT
                    AgentState.WORKING -> TaskState.RUNNING
                    AgentState.BLOCKED -> TaskState.BLOCKED
                    AgentState.FAILED -> TaskState.FAILED
                    AgentState.IDLE -> concluded(status.lastAction) ?: return@safely
                    AgentState.DONE -> return@safely
                }
            report(step, status.agentId, state, detail(state, status.lastAction))
        }

    override fun stepStarted(scenarioStep: String) = Unit

    override fun message(text: String) = Unit

    override fun runFinished(summary: RunSummary) = Unit

    // --- derivation -----------------------------------------------------------------------------------------------

    private fun stepRecorded(record: StepRecord) {
        val agentId = record.agentId ?: return
        if (record.kind == StepKind.SYSTEM && record.status == StepStatus.SKIPPED) {
            report(record.scenarioStep, agentId, TaskState.SKIPPED, record.detail, record.runId)
        }
    }

    private fun assertionRecorded(record: AssertionRecord) {
        if (record.type != ONLY_ONE_SUCCEEDS || record.agentId != null || record.verdict != Verdict.PASSED) return
        val losers =
            synchronized(lock) {
                if (record.runId != runId) return
                cells.filter { (key, state) -> key.first == record.scenarioStep && state == TaskState.FAILED }.keys.map { it.second }
            }
        losers.forEach { report(record.scenarioStep, it, TaskState.LOST_RACE, "başqa aktor qalib gəldi", record.runId) }
    }

    /** The result the runner writes into an agent's status when it ends a step: `ok: …` or `<failure key>: …`. */
    private fun concluded(lastAction: String?): TaskState? {
        val key = lastAction?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (key == OK) TaskState.PASSED else TaskState.FAILED
    }

    private fun detail(
        state: TaskState,
        lastAction: String?,
    ): String? =
        when (state) {
            TaskState.WAITING_EVENT, TaskState.FAILED, TaskState.BLOCKED -> lastAction
            else -> null
        }

    private fun report(
        step: String,
        agentId: AgentId,
        state: TaskState,
        detail: String?,
        recordRun: RunId? = null,
    ) {
        val run =
            synchronized(lock) {
                val current = runId ?: return
                if (recordRun != null && recordRun != current) return
                if (plan?.steps?.none { it.id == step } == true) return
                if (cells.put(step to agentId, state) == state) return
                current
            }
        dashboard.taskUpdated(TaskStateView(run, step, agentId, state, detail))
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.debug(e) { "a task state could not be derived; the run continues" }
        }
    }

    private companion object {
        const val OK = "ok"
        const val ONLY_ONE_SUCCEEDS = "only_one_succeeds"
    }
}
