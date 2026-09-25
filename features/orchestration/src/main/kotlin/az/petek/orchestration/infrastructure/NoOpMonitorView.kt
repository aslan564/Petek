package az.petek.orchestration.infrastructure

import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary

/** Monitor that ignores everything (tests, `--quiet`). Stateless, so a single instance is shared. */
object NoOpMonitorView : MonitorView {
    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) = Unit

    override fun agentUpdated(status: AgentStatus) = Unit

    override fun stepStarted(scenarioStep: String) = Unit

    override fun message(text: String) = Unit

    override fun runFinished(summary: RunSummary) = Unit
}
