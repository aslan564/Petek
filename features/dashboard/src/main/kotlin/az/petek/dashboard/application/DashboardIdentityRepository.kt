package az.petek.dashboard.application

import az.petek.core.ids.RunId
import az.petek.dashboard.domain.AgentProfile
import az.petek.dashboard.domain.DashboardUpdate
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRepository

/**
 * Decorator that shows each planned identity's department and registration mode on the [dashboard]. Only the public
 * [AgentProfile] reaches the dashboard: e-mails, phones and passwords stay in the repository. Everything else is plain
 * delegation; the dashboard sees a plan only after [delegate] stored it, and feeding it never throws.
 */
class DashboardIdentityRepository(
    private val delegate: IdentityRepository,
    private val dashboard: LiveDashboard,
) : IdentityRepository by delegate {
    override suspend fun replaceAll(
        runId: RunId,
        plan: IdentityPlan,
    ) {
        delegate.replaceAll(runId, plan)
        dashboard.submit { DashboardUpdate.AgentsPlanned(runId, plan.identities.map(AgentProfile::of), it) }
    }
}
