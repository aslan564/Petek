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

package az.petek.explorer.application

import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.Tenant
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.explorer.domain.ExplorationId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Names of the deterministic `run` functions a generated setup uses (the agent module's built-ins by default). */
data class SetupFunctions(
    val registerOwner: String = "register_owner",
    val seedCompany: String = "seed_company",
    val join: String = "register_and_login",
    /** The blind site-wide checks (Faza 13). */
    val siteHealth: String = "site_health",
    /** What a visitor sees on each page, checked by code (Faza 19). */
    val pageChecks: String = "page_checks",
    /** Someone else's object opened by its address (Faza 13). */
    val directUrl: String = "direct_url",
)

/**
 * The fixed frame of a generated campaign: a small team that can express every covered idea (two managers for races,
 * several employees for real-time fan-out), its departments, and the limits of the assertions it writes.
 *
 * @property oracleResources the resources whose objects the target's test API serves (`GET /test/<resource>/latest?by=`
 *   and `GET /test/<resource>/{id}`, docs/TARGET_CONTRACT.md). Only these get oracle id sources and oracle checks;
 *   an oracle path the target does not serve would make every generated check fail.
 */
data class ScenarioSettings(
    val team: RoleQuota = RoleQuota(admin = 1, manager = 2, employee = 3),
    val departments: List<String> = listOf("IT", "HR"),
    val seed: Long = 42,
    val budget: Budget = Budget(maxStepsPerAgent = 40, maxMinutes = 20),
    val setup: SetupFunctions = SetupFunctions(),
    val visibleWithin: Duration = 10.seconds,
    val waitTimeout: Duration = 30.seconds,
    val maxLatency: Duration = 5_000.milliseconds,
    val forbiddenStatus: Int = 403,
    val oracleResources: Set<String> = setOf("announcements", "tickets"),
    /** Whether the drafts are for a site with companies (the frame above) or without ([forSiteWithoutCompanies]). */
    val tenant: Tenant = Tenant.COMPANY,
    /** Without companies: the gate of each role's testers (`self`, `login`, `guest`); a role not listed signs up. */
    val gates: Map<Role, RegistrationMode> = emptyMap(),
) {
    init {
        if (tenant == Tenant.COMPANY) {
            require(team.admin == 1) { "a generated campaign has exactly one admin, who owns the company" }
            require(team.manager >= 0 && team.employee >= 0) { "role counts must not be negative" }
            require(departments.isNotEmpty()) { "a generated campaign needs at least one department" }
        } else {
            require(team.total > 0) { "a generated campaign needs at least one tester" }
        }
    }

    /** The gate of [role]'s testers on a site without companies. */
    fun gateOf(role: Role): RegistrationMode = gates[role] ?: RegistrationMode.SELF

    /**
     * The frame for a site without companies (Faza 13), with [testers] testers when the owner chose a count:
     *
     * - a site seen only anonymously: every tester is a visitor ([perRole] without a count);
     * - a site with a sign-up: every tester signs up ([signUp]), the testers shared evenly among the roles the explorer
     *   saw signed in ([perRole] each without a count, two so a race has its pair);
     * - a site with a sign-in but no sign-up: [perRole] testers of each such role sign in with the owner's accounts
     *   (the `login` gate), and the rest of the count are visitors, since each account serves one tester.
     *
     * No departments, no company setup.
     */
    fun forSiteWithoutCompanies(
        seenRoles: Collection<String>,
        perRole: Int = PER_ROLE,
        signUp: Boolean = true,
        testers: Int? = null,
    ): ScenarioSettings {
        require(testers == null || testers >= 1) { "a draft needs at least one tester, was $testers" }
        val signedIn =
            seenRoles
                .filter { it != VISITOR.key }
                .mapNotNull(Role::fromKey)
                .distinct()
                .sortedBy { it.key }
        val counts: Map<Role, Int>
        val gates: Map<Role, RegistrationMode>
        when {
            signedIn.isEmpty() -> {
                counts = mapOf(VISITOR to (testers ?: perRole))
                gates = mapOf(VISITOR to RegistrationMode.GUEST)
            }

            signUp -> {
                counts = evenly(testers ?: (perRole * signedIn.size), signedIn)
                gates = signedIn.associateWith { RegistrationMode.SELF }
            }

            else -> {
                val signing = minOf(testers ?: Int.MAX_VALUE, perRole * signedIn.size)
                val visitors = (testers ?: signing) - signing
                counts = evenly(signing, signedIn) + (if (visitors > 0) mapOf(VISITOR to visitors) else emptyMap())
                gates = signedIn.associateWith { RegistrationMode.LOGIN } + (VISITOR to RegistrationMode.GUEST)
            }
        }
        return copy(team = RoleQuota.of(counts), departments = emptyList(), tenant = Tenant.NONE, gates = gates)
    }

    /**
     * This frame with [testers] testers on a site with companies: the one admin, the managers it has (fewer when the
     * count leaves no room) and every other tester an employee.
     */
    fun withTesters(testers: Int): ScenarioSettings {
        require(testers >= 1) { "a draft needs at least one tester, was $testers" }
        if (tenant != Tenant.COMPANY || testers == team.total) return this
        val managers = minOf(team.manager, testers - 1)
        return copy(team = RoleQuota(admin = 1, manager = managers, employee = testers - 1 - managers))
    }

    /** [total] testers shared evenly among [roles] (the first ones get the rest); a role left without one is not listed. */
    private fun evenly(
        total: Int,
        roles: List<Role>,
    ): Map<Role, Int> =
        roles
            .mapIndexed { index, role -> role to total / roles.size + if (index < total % roles.size) 1 else 0 }
            .filter { it.second > 0 }
            .toMap()

    companion object {
        const val PER_ROLE = 2

        /** The role of a site's visitors: what the explorer saw without an account (`anonymous`). */
        val VISITOR: Role = checkNotNull(Role.fromKey("anonymous"))
    }
}

/** What to generate a draft from: the model of [explorationId], grounded by [instructions] (default: the exploration's). */
data class ScenarioRequest(
    val explorationId: ExplorationId,
    val instructions: String? = null,
    val maxIdeas: Int = 8,
    /** The target implements the `/test/...` API of docs/TARGET_CONTRACT.md: use it for ids and oracle checks. */
    val testApi: Boolean = false,
    val name: String? = null,
    /** `none`: the site has no companies; the draft signs its testers up (or lets them visit) instead of seeding one. */
    val tenant: Tenant = Tenant.COMPANY,
    /** How many testers the draft has (the owner's choice); null keeps the frame's own team. */
    val testers: Int? = null,
) {
    init {
        require(maxIdeas in 1..MAX_IDEAS) { "maxIdeas must be in 1..$MAX_IDEAS, was $maxIdeas" }
        require(name == null || name.isNotBlank()) { "a draft name must not be blank" }
        require(testers == null || testers in 1..MAX_TESTERS) { "testers must be in 1..$MAX_TESTERS, was $testers" }
    }

    companion object {
        const val MAX_IDEAS = 50
        const val MAX_TESTERS = 999
    }
}
