package az.petek.app.campaign

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.core.error.PetekException
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.ActorResolver

/**
 * `petek run --agents N`: shrinks a campaign to N testers for a quick trial while keeping its shape. The admin stays
 * (there is exactly one), and the other seats are shared out in the campaign's manager/employee and
 * invite/company-code ratios by largest remainder, so `30 → 12` keeps about one manager per five employees and an
 * even invite/code split. A role or registration mode the campaign uses keeps at least one seat whenever there are
 * enough seats, so steps written for it still have someone to run them. Names beyond N are dropped.
 *
 * Steps whose actors can no longer match anyone would be skipped silently by the runner; [uncoveredSteps] finds them
 * so the command can warn before the run starts.
 */
object CampaignScaler {
    fun scale(
        campaign: Campaign,
        agents: Int,
    ): Campaign {
        val settings = campaign.settings
        if (agents < 1) throw ScalingException("--agents must be at least 1, was $agents")
        if (agents > settings.testers) {
            throw ScalingException("--agents $agents is more than the campaign's ${settings.testers} testers; it can only reduce them")
        }
        if (agents == settings.testers) return campaign
        val admins = minOf(settings.roles.admin, 1)
        if (agents < admins + 1 && settings.roles.manager + settings.roles.employee > 0) {
            throw ScalingException("--agents $agents leaves no tester besides the admin; use at least ${admins + 1}")
        }
        val others = agents - admins
        val (managers, employees) = apportion(others, listOf(settings.roles.manager, settings.roles.employee))
        val (invite, companyCode) = apportion(others, listOf(settings.registration.invite, settings.registration.companyCode))
        return campaign.copy(
            settings =
                settings.copy(
                    testers = agents,
                    roles = RoleQuota(admin = admins, manager = managers, employee = employees),
                    registration = RegistrationQuota(invite = invite, companyCode = companyCode),
                    names = settings.names.take(agents),
                    name = "${settings.name} ($agents of ${settings.testers} agents)",
                ),
        )
    }

    /**
     * The web panel's tester count: [campaign] with exactly [testers] testers, fewer or more than it has. Shrinking works
     * like [scale] but keeps the campaign's name (the panel shows the count next to it, and triage finds the scenario by
     * it). Growing shares the seats in the campaign's ratios as well, and additionally keeps at least one manager per
     * department when the campaign has managers (so `manager[<department>]` steps keep an actor) while leaving an
     * employee when it has employees. Invitations always cover the managers. The result still has to pass the
     * campaign validator (the caller checks it).
     */
    fun resize(
        campaign: Campaign,
        testers: Int,
    ): Campaign {
        val settings = campaign.settings
        if (testers < 1) throw ScalingException("the tester count must be at least 1, was $testers")
        if (testers == settings.testers) return campaign
        if (testers < settings.testers) return scale(campaign, testers).let { it.copy(settings = it.settings.copy(name = settings.name)) }
        val roles = settings.roles
        val admins = minOf(roles.admin, 1)
        val others = testers - admins
        val shares = apportion(others, listOf(roles.manager, roles.employee))
        var managers = shares[0]
        var employees = shares[1]
        if (roles.manager > 0) {
            val keepEmployee = if (roles.employee > 0) 1 else 0
            val wanted = minOf(settings.departments.size, others - keepEmployee)
            if (managers < wanted) {
                employees -= wanted - managers
                managers = wanted
            }
        }
        val joining = managers + employees
        val invite =
            apportion(joining, listOf(settings.registration.invite, settings.registration.companyCode))[0]
                .coerceAtLeast(managers)
                .coerceAtMost(joining)
        return campaign.copy(
            settings =
                settings.copy(
                    testers = testers,
                    roles = RoleQuota(admin = admins, manager = managers, employee = employees),
                    registration = RegistrationQuota(invite = invite, companyCode = joining - invite),
                ),
        )
    }

    /** Steps of [campaign] whose actor expression matches none of [identities]. */
    fun uncoveredSteps(
        campaign: Campaign,
        identities: List<Identity>,
        resolver: ActorResolver,
    ): List<ScenarioStep> = campaign.allSteps.filter { resolver.resolve(it.actors, identities).isEmpty() }

    /**
     * Splits [total] seats in proportion to [weights] (largest remainder; ties go to the earlier category), then gives
     * every category with a positive weight at least one seat, taken from the largest one, as long as seats allow.
     */
    internal fun apportion(
        total: Int,
        weights: List<Int>,
    ): List<Int> {
        require(total >= 0) { "total must not be negative, was $total" }
        require(weights.all { it >= 0 }) { "weights must not be negative, were $weights" }
        val sum = weights.sum()
        if (sum == 0 || total == 0) return weights.map { 0 }
        val exact = weights.map { it.toDouble() * total / sum }
        val seats = exact.map { it.toInt() }.toMutableList()
        val leftover = total - seats.sum()
        exact.indices
            .sortedWith(compareByDescending<Int> { exact[it] - seats[it] }.thenBy { it })
            .take(leftover)
            .forEach { seats[it]++ }
        guaranteeOneEach(seats, weights)
        return seats
    }

    private fun guaranteeOneEach(
        seats: MutableList<Int>,
        weights: List<Int>,
    ) {
        while (true) {
            val empty = weights.indices.firstOrNull { weights[it] > 0 && seats[it] == 0 } ?: return
            val donor = seats.indices.filter { seats[it] > 1 }.maxByOrNull { seats[it] } ?: return
            seats[donor]--
            seats[empty]++
        }
    }
}

/** `--agents` cannot be applied to this campaign. */
class ScalingException(
    message: String,
) : PetekException(message)
