package az.petek.orchestration.infrastructure

import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Monitor for non-interactive output (CI, piped logs): run and step boundaries and harness messages at INFO,
 * agents that end up blocked or failed at WARN, every other agent transition at DEBUG. Logging calls are cheap and
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

    private fun describe(status: AgentStatus): String =
        "${status.agentId} (${status.role}) ${status.state.name.lowercase()}" +
            (status.scenarioStep?.let { " in '$it'" } ?: "") +
            (status.lastAction?.let { ": $it" } ?: "")
}
