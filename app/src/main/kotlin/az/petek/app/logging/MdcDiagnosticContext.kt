package az.petek.app.logging

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.orchestration.application.DiagnosticContext
import kotlin.coroutines.CoroutineContext

/**
 * Attaches `run_id` and `agent_id` to every log line written while the runner works for a run or an agent
 * (CLAUDE.md: "Loglarda MDC"). Harness-level work has no agent, so `agent_id` is cleared for it.
 */
object MdcDiagnosticContext : DiagnosticContext {
    const val RUN_ID = "run_id"
    const val AGENT_ID = "agent_id"

    override fun of(
        runId: RunId,
        agentId: AgentId?,
    ): CoroutineContext = MdcContext(mapOf(RUN_ID to runId.value, AGENT_ID to agentId?.value))
}
