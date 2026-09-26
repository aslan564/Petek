/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.app.campaign

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.core.error.PetekException
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.ActorResolver

/**
 * `petek run --testers N` and the panel's tester count: resizes a campaign to N testers while keeping its shape, fewer
 * for a quick trial or more for load. The admin stays (there is exactly one), and the other seats are shared out in the
 * campaign's manager/employee and invite/company-code ratios by largest remainder, so `30 → 12` keeps about one manager
 * per five employees and an even invite/code split, and `30 → 60` doubles both. A role or registration mode the campaign
 * uses keeps at least one seat whenever there are enough seats, so steps written for it still have someone to run them,
 * and managers are always invited (the company-code form has no role field). Given names beyond N are dropped; testers
 * beyond the given names get catalog names from the identity generator.
 *
 * Steps whose actors can no longer match anyone would be skipped silently by the runner; [uncoveredSteps] finds them
 * so the command can warn before the run starts.
 */
object CampaignScaler {
    /** `petek run --testers N`: [resize], with the new size in the campaign's name so reports tell the runs apart. */
    fun scale(
        campaign: Campaign,
        agents: Int,
    ): Campaign {
        val resized = resize(campaign, agents)
        if (resized === campaign) return campaign
        return resized.copy(
            settings =
                resized.settings.copy(
                    name = "${campaign.settings.name} ($agents testers, scaled from ${campaign.settings.testers})",
                ),
        )
    }

    /**
     * The web panel's tester count: [campaign] with exactly [testers] testers, fewer or more than it has, under its own
     * name (the panel shows the count next to it, and triage finds the scenario by it). Growing additionally keeps at
     * least one manager per department when the campaign has managers (so `manager[<department>]` steps keep an actor)
     * while leaving an employee when it has employees. The result still has to pass the campaign validator (the caller
     * checks it).
     */
    fun resize(
        campaign: Campaign,
        testers: Int,
    ): Campaign {
        val settings = campaign.settings
        if (testers < 1) throw ScalingException("the tester count must be at least 1, was $testers")
        if (testers == settings.testers) return campaign
        val roles = settings.roles
        val admins = minOf(roles.admin, 1)
        val others = testers - admins
        if (others < 1 && roles.manager + roles.employee > 0) {
            throw ScalingException("$testers testers leave no tester besides the admin; use at least ${admins + 1}")
        }
        if (others > 0 && roles.manager + roles.employee == 0) {
            throw ScalingException("$testers testers: the campaign has only the admin, so there is no role to give more testers")
        }
        var (managers, employees) = apportion(others, listOf(roles.manager, roles.employee))
        if (testers > settings.testers && roles.manager > 0) {
            val keepEmployee = if (roles.employee > 0) 1 else 0
            val wanted = minOf(settings.departments.size, others - keepEmployee)
            if (managers < wanted) {
                employees -= wanted - managers
                managers = wanted
            }
        }
        // Managers always join by invitation: the company-code form has no role field.
        val invite =
            apportion(others, listOf(settings.registration.invite, settings.registration.companyCode))[0]
                .coerceAtLeast(managers)
                .coerceAtMost(others)
        return campaign.copy(
            settings =
                settings.copy(
                    testers = testers,
                    roles = RoleQuota(admin = admins, manager = managers, employee = employees),
                    registration = RegistrationQuota(invite = invite, companyCode = others - invite),
                    names = settings.names.take(testers),
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

/** `--testers` cannot be applied to this campaign. */
class ScalingException(
    message: String,
) : PetekException(message)
