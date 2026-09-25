package az.petek.orchestration.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunPlan
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.domain.TaskState
import az.petek.orchestration.domain.TaskUpdate
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Monitor for non-interactive output (CI, piped logs): run and step boundaries, harness messages, the size of the
 * task plan, published events, missed receptions and tasks that failed, got blocked or lost a race at INFO; agents
 * that end up blocked or failed at WARN; every other agent and task transition at DEBUG. Logging calls are cheap and
 * never block the run beyond the logging backend's own appenders.
 */
class LoggingMonitorView(
    private val logger: KLogger = KotlinLogging.logger(LoggingMonitorView::class.qualifiedName ?: "LoggingMonitorView"),
) : MonitorView {
    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) {
        logger.info { "run $runId started with ${agents.size} agents" }
    }

    override fun agentUpdated(status: AgentStatus) {
        val line = describe(status)
        when (status.state) {
            AgentState.BLOCKED, AgentState.FAILED -> logger.warn { line }
            else -> logger.debug { line }
        }
    }

    override fun stepStarted(scenarioStep: String) {
        logger.info { "step '$scenarioStep' started" }
    }

    override fun message(text: String) {
        logger.info { text }
    }

    override fun runFinished(summary: RunSummary) {
        logger.info {
            "run ${summary.runId} finished: ${summary.outcome} (steps passed ${summary.stepsPassed}, " +
                "failed ${summary.stepsFailed}; assertions failed ${summary.assertionsFailed}; " +
                "failed agents ${summary.failedAgents}; ${summary.durationMs} ms; report ${summary.reportDirectory ?: "-"})"
        }
    }

    override fun planReady(plan: RunPlan) {
        logger.info { "run ${plan.runId} plan: ${plan.steps.size} steps, ${plan.steps.sumOf { it.resolvedAgents.size }} tasks" }
        plan.steps.forEach { step ->
            logger.debug {
                "plan step '${step.id}' (${step.phase.name.lowercase()}, ${step.actionKind.name.lowercase()}): " +
                    step.resolvedAgents.joinToString(", ").ifEmpty { "no agents" }
            }
        }
    }

    override fun taskUpdated(update: TaskUpdate) {
        val line = "task '${update.stepId}' ${update.agentId} ${update.state.name.lowercase()}" + (update.detail?.let { ": $it" } ?: "")
        when (update.state) {
            TaskState.FAILED, TaskState.BLOCKED, TaskState.LOST_RACE -> logger.info { line }
            else -> logger.debug { line }
        }
    }

    override fun eventPublished(event: PublishedEvent) {
        logger.info { "event ${event.name} published by ${event.emitter} (object ${event.objectId ?: "-"})" }
    }

    override fun eventReceived(
        eventName: String,
        agentId: AgentId,
        latencyMs: Long?,
        received: Boolean,
    ) {
        if (received) {
            logger.debug { "$agentId received $eventName" + (latencyMs?.let { " after $it ms" } ?: "") }
        } else {
            logger.info { "$agentId missed $eventName" }
        }
    }

    private fun describe(status: AgentStatus): String =
        "${status.agentId} (${status.role}) ${status.state.name.lowercase()}" +
            (status.scenarioStep?.let { " in '$it'" } ?: "") +
            (status.lastAction?.let { ": $it" } ?: "")
}
