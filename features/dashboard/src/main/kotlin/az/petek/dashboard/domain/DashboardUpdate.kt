package az.petek.dashboard.domain

import az.petek.core.ids.RunId
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepRecord
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.RunOutcome

/**
 * Something the dashboard learned. [at] is when the harness observed it (stamped by the dashboard's clock, or taken
 * from the evidence when a finished run is replayed); it is used for facts that carry no time of their own.
 *
 * Run scoping (see [DashboardState.apply]): [RunCreated] and [RunStarted] name the run being shown and start a fresh
 * board when they name a different one. Updates that carry a run id ([AgentsPlanned], the evidence records) adopt
 * their run when nothing is shown yet or the shown run has ended, and are ignored while another run is still going.
 * Updates without a run id apply to whatever is shown. [PlanReady] names its run like [RunStarted] does.
 *
 * Events and receipts are idempotent: the same event id (or the same receiver of an event) counts once, so both the
 * evidence recorder and the orchestrator may report them.
 */
sealed interface DashboardUpdate {
    val at: HarnessTimestamp

    /** The run record was created: campaign, target and start time. */
    data class RunCreated(
        val run: RunRecord,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    /** The identity registry of [runId]: department and registration mode per agent. */
    data class AgentsPlanned(
        val runId: RunId,
        val agents: List<AgentProfile>,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    /** The orchestrator's board started with every agent (from `MonitorView.runStarted`). */
    data class RunStarted(
        val runId: RunId,
        val agents: List<AgentStatus>,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    /** The orchestrator's view of one agent changed; its [AgentStatus.state] is authoritative. */
    data class AgentUpdated(
        val status: AgentStatus,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    data class ScenarioStepStarted(
        val scenarioStep: String,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    /** A harness message (abort reasons, excluded agents, teardown problems). */
    data class Message(
        val text: String,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    /** The run ended; a null [outcome] means it never recorded an end (only in replays of interrupted runs). */
    data class RunEnded(
        val runId: RunId,
        val outcome: RunOutcome?,
        val durationMs: Long,
        val reportPath: String?,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    data class StepRecorded(
        val record: StepRecord,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    data class ArtifactRecorded(
        val record: ArtifactRecord,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    data class EventRecorded(
        val record: EventRecord,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    data class ReceiptRecorded(
        val record: EventReceipt,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    data class AssertionRecorded(
        val record: AssertionRecord,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    data class FindingRecorded(
        val record: FindingRecord,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    /** The orchestrator's plan of a run; a plan without a run id applies to whatever run is shown. */
    data class PlanReady(
        val plan: RunPlanView,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate

    /** One cell of the task matrix changed; the latest report per (step, agent) wins. */
    data class TaskUpdated(
        val task: TaskStateView,
        override val at: HarnessTimestamp,
    ) : DashboardUpdate
}
