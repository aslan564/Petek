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

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.Tenant
import az.petek.core.error.PetekException
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.ActorResolver
import az.petek.orchestration.domain.Waves

/**
 * `petek run --testers N` and the panel's tester count: resizes a campaign to N testers while keeping its shape, fewer
 * for a quick trial or more for load. The admin stays (there is exactly one), and the other seats are shared out in the
 * campaign's manager/employee and invite/company-code ratios by largest remainder, so `30 → 12` keeps about one manager
 * per five employees and an even invite/code split, and `30 → 60` doubles both. A role or registration mode the campaign
 * uses keeps at least one seat whenever there are enough seats, so steps written for it still have someone to run them,
 * and managers are always invited (the company-code form has no role field). Given names beyond N are dropped; testers
 * beyond the given names get catalog names from the identity generator.
 *
 * A campaign without companies (`tenant: none`) shares its own roles and gates out the same way instead.
 *
 * Steps whose actors can no longer match anyone would be skipped silently by the runner; [uncoveredSteps] finds them
 * so the command can warn before the run starts, [racesSplitByWaves] finds the races `wave_size` leaves with a
 * single racer in a wave, where they fail, and [waitsWithoutEmitter] the receivers it puts in a wave without the tester
 * that emits their event, where they are skipped.
 */
object CampaignScaler {
    /** A race step and the waves (numbered from 1) that hold only one of its racers. */
    data class SplitRace(
        val step: ScenarioStep,
        val waves: List<Int>,
    )

    /**
     * A `wait_for` [step], the [emitter] step of its event, and the [waves] (from 1) that hold receivers but no emitter;
     * [covered] when some other wave holds both, so the step is still checked there.
     */
    data class WaitWithoutEmitter(
        val step: ScenarioStep,
        val emitter: ScenarioStep,
        val waves: List<Int>,
        val covered: Boolean,
    )

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
        if (settings.tenant == Tenant.NONE) return resizeWithoutCompanies(campaign, testers)
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

    /**
     * A site without companies: the campaign's own roles and its gates are shared out in their ratios, each keeping a
     * seat while seats allow, so `2 visitors -> 30` makes 30 visitors. Testers who sign in with the owner's accounts
     * (`login`) never become more than the campaign had, since each needs an account of its own; the other seats go to
     * those who sign up (`self`) or visit (`guest`).
     */
    private fun resizeWithoutCompanies(
        campaign: Campaign,
        testers: Int,
    ): Campaign {
        val settings = campaign.settings
        val used = settings.roles.counts.filterValues { it > 0 }
        if (used.isEmpty()) throw ScalingException("the campaign names no role to give $testers testers")
        val roles = RoleQuota.of(used.keys.zip(apportion(testers, used.values.toList())).toMap())
        val gates = settings.registration
        val login = minOf(apportion(testers, listOf(gates.login, gates.self, gates.guest))[0], gates.login)
        val others = testers - login
        if (others > 0 && gates.self + gates.guest == 0) {
            throw ScalingException(
                "every tester of the campaign signs in with one of the owner's ${gates.login} account(s), so $testers testers " +
                    "need more accounts; or let testers sign up (registration: self) or visit (guest)",
            )
        }
        val (self, guest) = apportion(others, listOf(gates.self, gates.guest))
        return campaign.copy(
            settings =
                settings.copy(
                    testers = testers,
                    roles = roles,
                    registration = RegistrationQuota(invite = 0, companyCode = 0, self = self, login = login, guest = guest),
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
     * Race steps (`only_one_succeeds`) of [campaign] that its `wave_size` leaves with a single racer in some wave. The
     * runner keeps the racers of a step in one wave ([Waves]), so this happens only to a race with more racers than a
     * wave holds, and a race with one racer never passes. Empty without waves; a wave the step has no racer in skips it.
     */
    fun racesSplitByWaves(
        campaign: Campaign,
        identities: List<Identity>,
        resolver: ActorResolver,
    ): List<SplitRace> {
        val plan = Waves.plan(campaign, identities, resolver) ?: return emptyList()
        return campaign.allSteps
            .filter { step -> step.assertions.any { it is AssertionSpec.OnlyOneSucceeds } }
            .map { step ->
                val alone = plan.waves.indices.filter { resolver.resolve(step.actors, plan.live(it)).size == 1 }
                SplitRace(step, alone.map { it + 1 })
            }.filter { it.waves.isNotEmpty() }
    }

    /**
     * `wait_for` steps whose receivers some wave of `wave_size` holds without a tester of the step that emits their
     * event (Faza 24.7): the runner skips those receivers, and a step no wave holds with its emitter is never checked
     * (`not_covered`). The residents of every wave ([az.petek.orchestration.domain.WavePlan.residents]) emit in each,
     * and a setup step's event serves the waves after its own too (Faza 24.11). Empty without waves.
     */
    fun waitsWithoutEmitter(
        campaign: Campaign,
        identities: List<Identity>,
        resolver: ActorResolver,
    ): List<WaitWithoutEmitter> {
        val plan = Waves.plan(campaign, identities, resolver) ?: return emptyList()
        val emitters = campaign.allSteps.mapNotNull { step -> step.emits?.let { it.event to step } }.toMap()
        return campaign.allSteps.mapNotNull { step ->
            val emitter = step.waitFor?.let { emitters[it.event] } ?: return@mapNotNull null
            val emits = plan.waves.indices.map { resolver.resolve(emitter.actors, plan.live(it)).isNotEmpty() }
            val withReceivers = plan.waves.indices.filter { resolver.resolve(step.actors, plan.live(it)).isNotEmpty() }
            val alone =
                withReceivers.filterNot { wave ->
                    emits[wave] || (emitter.phase == StepPhase.SETUP && emits.take(wave).any { it })
                }
            if (alone.isEmpty()) null else WaitWithoutEmitter(step, emitter, alone.map { it + 1 }, alone.size < withReceivers.size)
        }
    }

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
