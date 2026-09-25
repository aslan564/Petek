package az.petek.orchestration.infrastructure

import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Fans every notification out to several views (e.g. the live board and the log file), in list order. A view that
 * throws is logged and skipped so the others still see the call and the run is never affected.
 */
class CompositeMonitorView(
    private val views: List<MonitorView>,
) : MonitorView {
    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) = each { it.runStarted(runId, agents) }

    override fun agentUpdated(status: AgentStatus) = each { it.agentUpdated(status) }

    override fun stepStarted(scenarioStep: String) = each { it.stepStarted(scenarioStep) }

    override fun message(text: String) = each { it.message(text) }

    override fun runFinished(summary: RunSummary) = each { it.runFinished(summary) }

    private inline fun each(call: (MonitorView) -> Unit) {
        views.forEach { view ->
            try {
                call(view)
            } catch (e: Exception) {
                logger.warn(e) { "monitor view ${view::class.simpleName} failed" }
            }
        }
    }
}
