/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.StepContext
import az.petek.core.model.RegistrationMode
import az.petek.evidence.domain.StepStatus
import az.petek.oracle.domain.Invitee
import az.petek.oracle.domain.SeedCompanyRequest
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany

/**
 * `seed_company` (admin): prepares the company the other testers join.
 *
 * With the test API: finds the company owned by the admin (retrying briefly, the sign-up may just have finished),
 * refuses one that is not `is_test`, seeds the roster's departments (roster order, no duplicates) and invitations
 * for every [RegistrationMode.INVITE] identity, then publishes `company_id`, `company_code` and each returned
 * invitation link to [SharedRunState]. The object id is the company id.
 *
 * Without the test API: reads the code from the `/company` page and publishes it. Invitations cannot be created
 * then, so the step fails with `missing_prerequisite` when the roster has invitation-mode testers.
 *
 * In both cases a company code that cannot be found at all fails the step only when company-code testers depend
 * on it; they would otherwise each wait [RunFunctionSettings.companyCodeTimeout] for nothing.
 */
internal class SeedCompanyRunFunction(
    private val engine: RunEngine,
    private val flows: TargetFlows,
    private val oracle: TargetOracle,
    private val settings: RunFunctionSettings,
) : RunFunction {
    override val name: String = RunFunctions.SEED_COMPANY

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome = engine.execute(name, runtime, step) { if (oracle.isAvailable) seedThroughTestApi() else publishCodeFromUi() }

    private suspend fun RunTrace.seedThroughTestApi(): ActionOutcome {
        val company = ownedCompany()
        val departments = runtime.roster.mapNotNull { it.department?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        val invites =
            runtime.roster
                .filter { it.registration == RegistrationMode.INVITE }
                .map { Invitee(it.email, it.displayName, it.role.key, it.department) }
        val result =
            act("seed ${departments.size} departments and ${invites.size} invitations into ${company.id}") {
                oracle.seedCompany(SeedCompanyRequest(company.id, departments, invites))
            }
        val shared = runtime.shared
        // Shared values are write-once: what the joiners will read is what is published, not what this answer said.
        val companyId = publish(SharedRunState.COMPANY_ID, result.companyId)
        result.inviteLinks.forEach { (email, link) -> publish(SharedRunState.inviteLink(email), link) }
        val code = (result.companyCode ?: company.code ?: codeFromUi())?.let { publish(SharedRunState.COMPANY_CODE, it) }
        val summary =
            "Seeded company $companyId: ${departments.size} departments, ${invites.size} invitations " +
                "(${result.inviteLinks.size} links returned), company code ${code ?: "unknown"}."
        val codeJoiners = runtime.roster.count { it.registration == RegistrationMode.COMPANY_CODE }
        if (code == null && codeJoiners > 0) {
            return ActionOutcome(
                status = ActionStatus.FAILED,
                summary = "$summary $codeJoiners company-code testers cannot join without it.",
                objectId = result.companyId,
                failureReason = FailureReason.MISSING_PREREQUISITE,
            )
        }
        return succeeded(summary, objectId = result.companyId)
    }

    private suspend fun RunTrace.ownedCompany(): TestCompany {
        val email = runtime.identity.email
        val company =
            lookup("look up the company owned by $email") { flows.retryOracle { oracle.companyByOwner(email) } }
                ?: throw RunFailure(
                    FailureReason.MISSING_PREREQUISITE,
                    "The test API knows no company owned by $email; the owner sign-up must succeed first.",
                )
        if (!company.isTest) {
            throw RunFailure(FailureReason.MISSING_PREREQUISITE, "Company ${company.id} is not flagged is_test; refusing to seed it.")
        }
        return company
    }

    /** Publishes [value] under [key] and returns what the key holds afterwards: [value], or an earlier publication (noted). */
    private suspend fun RunTrace.publish(
        key: String,
        value: String,
    ): String {
        if (runtime.shared.put(key, value)) return value
        val kept = runtime.shared.get(key) ?: value
        note("publish shared.$key", StepStatus.PASSED, "already published as '$kept'; kept (write-once), this step said '$value'")
        return kept
    }

    private suspend fun RunTrace.publishCodeFromUi(): ActionOutcome {
        val code = codeFromUi()?.let { publish(SharedRunState.COMPANY_CODE, it) }
        val invitees = runtime.roster.count { it.registration == RegistrationMode.INVITE }
        val codeJoiners = runtime.roster.count { it.registration == RegistrationMode.COMPANY_CODE }
        return when {
            invitees > 0 -> {
                failed(
                    FailureReason.MISSING_PREREQUISITE,
                    "The test API is not available, so $invitees invitation-mode testers cannot be invited" +
                        (code?.let { "; company code $it was published for the others." } ?: "."),
                )
            }

            code == null && codeJoiners > 0 -> {
                failed(
                    FailureReason.MISSING_PREREQUISITE,
                    "The test API is not available and the company code is not shown on the company page; " +
                        "$codeJoiners company-code testers cannot join.",
                )
            }

            code == null -> {
                succeeded("The test API is not available and no company code is shown; nobody needs to join.")
            }

            else -> {
                succeeded("The test API is not available; published company code $code from the company page.")
            }
        }
    }

    private suspend fun RunTrace.codeFromUi(): String? {
        open("company")
        if (!waitFor("company.code", settings.uiTimeout)) return null
        return readText("company.code")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
