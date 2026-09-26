/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
     * The frame for a site without companies: every role the explorer saw signed in gets [perRole] testers (two, so a
     * race has its pair) who sign up on their own; a site seen only anonymously gets [perRole] visitors. No departments,
     * no company setup (Faza 13).
     */
    fun forSiteWithoutCompanies(
        seenRoles: Collection<String>,
        perRole: Int = PER_ROLE,
    ): ScenarioSettings {
        val signedIn =
            seenRoles
                .filter { it != VISITOR.key }
                .mapNotNull(Role::fromKey)
                .distinct()
                .sortedBy { it.key }
        val roles = signedIn.ifEmpty { listOf(VISITOR) }
        return copy(
            team = RoleQuota.of(roles.associateWith { perRole }),
            departments = emptyList(),
            tenant = Tenant.NONE,
            gates = if (signedIn.isEmpty()) mapOf(VISITOR to RegistrationMode.GUEST) else emptyMap(),
        )
    }

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
) {
    init {
        require(maxIdeas in 1..MAX_IDEAS) { "maxIdeas must be in 1..$MAX_IDEAS, was $maxIdeas" }
        require(name == null || name.isNotBlank()) { "a draft name must not be blank" }
    }

    companion object {
        const val MAX_IDEAS = 50
    }
}
