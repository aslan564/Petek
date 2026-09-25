package az.petek.reporting.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId

/**
 * Display names of a run's agents for the report. Reporting depends only on evidence, not on the identity feature,
 * so the composition root adapts the identity repository to this port. Agents without a name are shown by id.
 */
fun interface AgentDirectory {
    suspend fun names(runId: RunId): Map<AgentId, String>

    companion object {
        /** No names known: the report falls back to agent ids. */
        val NONE: AgentDirectory = AgentDirectory { emptyMap() }
    }
}
