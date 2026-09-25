package az.petek.orchestration.domain

import az.petek.campaign.domain.ActorExpression
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

/** Port for the live console board (Mordant) or any other observer. Must never block the run. */
interface MonitorView {
    fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    )

    fun agentUpdated(status: AgentStatus)

    fun stepStarted(scenarioStep: String)

    fun message(text: String)

    fun runFinished(summary: RunSummary)
}

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
