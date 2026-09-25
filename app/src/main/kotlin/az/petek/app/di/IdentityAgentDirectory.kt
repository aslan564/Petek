package az.petek.app.di

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.identity.domain.IdentityRepository
import az.petek.reporting.domain.AgentDirectory

/** Gives the report the display names of a run's testers (reporting cannot see the identity feature itself). */
class IdentityAgentDirectory(
    private val identities: IdentityRepository,
) : AgentDirectory {
    override suspend fun names(runId: RunId): Map<AgentId, String> = identities.findByRun(runId).associate { it.agentId to it.displayName }
}
