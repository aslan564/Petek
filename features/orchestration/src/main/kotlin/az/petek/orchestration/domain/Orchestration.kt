package az.petek.orchestration.domain

import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.StepPhase
import az.petek.core.ids.AgentId
import az.petek.core.ids.EventId
import az.petek.core.ids.RunId
import az.petek.core.time.HarnessTimestamp
import az.petek.identity.domain.Identity
import kotlin.time.Duration

/** An event published by an actor (`emits`). [t0] is taken by the harness at publish time. */
data class PublishedEvent(
    val eventId: EventId,
    val name: String,
    val objectId: String?,
    val emitter: AgentId,
    val t0: HarnessTimestamp,
    /** Monotonic publish order within the run. */
    val sequence: Long,
)

/**
 * Coordination channel for `emits` / `wait_for` (in-process in the MVP; Redis/NATS later behind this port).
 * Events are retained for the whole run, so a waiter that arrives late still sees an event emitted earlier.
 */
interface EventBus {
    suspend fun publish(
        name: String,
        objectId: String?,
        emitter: AgentId,
    ): PublishedEvent

    /** Latest event named [name] with sequence > [afterSequence], waiting up to [timeout]; null on timeout. */
    suspend fun await(
        name: String,
        afterSequence: Long,
        timeout: Duration,
    ): PublishedEvent?

    fun latest(name: String): PublishedEvent?

    fun latestAny(): PublishedEvent?
}

/** Resolves an actor expression against the run's identities (pure, deterministic order by agent id). */
fun interface ActorResolver {
    fun resolve(
        expression: ActorExpression,
        identities: List<Identity>,
    ): List<Identity>
}

enum class AgentState { IDLE, WORKING, WAITING, BLOCKED, FAILED, DONE }

data class AgentStatus(
    val agentId: AgentId,
    val displayName: String,
    val role: String,
    val state: AgentState,
    val scenarioStep: String?,
    val lastAction: String?,
    val updatedAt: HarnessTimestamp,
)

/**
 * Port for the live console board (Mordant) or any other observer (the web panel). Must never block the run: every
 * call is made on the run's own coroutines, often from many agents at once, so an implementation only records and
 * returns. A view may ignore what it does not show; the orchestrator's task plan and the event timeline therefore
 * have default no-op bodies.
 */
interface MonitorView {
    fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    )

    fun agentUpdated(status: AgentStatus)

    fun stepStarted(scenarioStep: String)

    fun message(text: String)

    fun runFinished(summary: RunSummary)

    /**
     * The orchestrator's task plan: every step with the agents who act in it. Sent once the identities and their
     * browser sessions are ready, and again whenever the resolution changes (an agent failed and is excluded from
     * later steps); steps already run keep the agents that ran them. Every step × agent pair starts as
     * [TaskState.PENDING] (reported through [taskUpdated]).
     */
    fun planReady(plan: RunPlan) = Unit

    /** One step × agent task changed its state; sent for every transition, in the order they happened per task. */
    fun taskUpdated(update: TaskUpdate) = Unit

    /** An actor's `emits` published [event]; its t0 is the harness time of publishing. */
    fun eventPublished(event: PublishedEvent) = Unit

    /**
     * [agentId] was waiting for [eventName]: [received] with the measured delivery latency (t1 − t0) when a
     * `visible_text` measured it, or missed (not seen, not published in time).
     */
    fun eventReceived(
        eventName: String,
        agentId: AgentId,
        latencyMs: Long?,
        received: Boolean,
    ) = Unit
}

/** What a planned step makes its actors do. */
enum class PlannedActionKind { DO, RUN, NONE }

/**
 * One step of a [RunPlan] as a live view shows it. [actorsRaw] is the actor expression as written, [actionText] the
 * `do` instruction or the `run` function with its arguments (templates unrendered; campaign files never contain
 * secrets), [emits] and [waitFor] event names, and [resolvedAgents] the agents that act in it, by agent id.
 */
data class PlannedStep(
    val id: String,
    val phase: StepPhase,
    val actorsRaw: String,
    val actionKind: PlannedActionKind,
    val actionText: String,
    val emits: String?,
    val waitFor: String?,
    val parallel: Boolean,
    val assertionTypes: List<String>,
    val resolvedAgents: List<AgentId>,
)

/** The orchestrator's plan of one run: setup steps, then main steps, in execution order. */
data class RunPlan(
    val runId: RunId,
    val steps: List<PlannedStep>,
)

/**
 * State of one step × agent task. [WAITING_EVENT] waits for a `wait_for` event, [RUNNING] performs the action; the
 * others are final: [PASSED] (also an expected permission refusal), [FAILED], [BLOCKED] (no progress, cancelled by
 * the watchdog), [SKIPPED] (agent failed earlier, run aborted, step never reached) and [LOST_RACE] (the agent lost a
 * `parallel` race, which is the expected outcome for every agent but the winner).
 */
enum class TaskState {
    PENDING,
    WAITING_EVENT,
    RUNNING,
    PASSED,
    FAILED,
    BLOCKED,
    SKIPPED,
    LOST_RACE,
    ;

    /** Nothing follows a final state for the same task. */
    val isFinal: Boolean get() = this != PENDING && this != WAITING_EVENT && this != RUNNING
}

/** A task's new [state] at harness time [at]; [detail] says why, e.g. `wait_for ticket_created` or `lost_race: ...`. */
data class TaskUpdate(
    val stepId: String,
    val agentId: AgentId,
    val state: TaskState,
    val detail: String?,
    val at: HarnessTimestamp,
)

data class RunOptions(
    val repeatGroup: String? = null,
    val repeatIndex: Int? = null,
    /** Keep the test company after the run (debugging). Teardown is the default. */
    val keepData: Boolean = false,
    /** Seconds without progress before an agent is marked `blocked` and moves on. */
    val inactivityTimeout: Duration = Duration.parse("120s"),
)

enum class RunOutcome { PASSED, FAILED, ABORTED }

data class RunSummary(
    val runId: RunId,
    val outcome: RunOutcome,
    val stepsPassed: Int,
    val stepsFailed: Int,
    val assertionsFailed: Int,
    val failedAgents: Int,
    val reportDirectory: String?,
    val durationMs: Long,
)
