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

package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.StepContext
import az.petek.campaign.domain.FlowNames
import az.petek.core.model.RegistrationMode
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany

/**
 * `register_owner` (admin; arg `company`, default [DEFAULT_COMPANY], the flows' `{campaign.company}`): the owner
 * sign-up without the LLM. The target profile's `register_owner` flow (contract: `/register` form -> e-mail code ->
 * phone code when asked -> signed in -> identity check -> storage state saved); with the test API available, the new
 * company's id and code are then published to [SharedRunState] for the others. A site whose sign-up does not end
 * signed in is signed in with the `login` flow afterwards, which may use the published `{shared.company_code}`.
 */
internal class RegisterOwnerRunFunction(
    private val engine: RunEngine,
    private val flows: FlowRunner,
    private val targetFlows: TargetFlows,
    private val oracle: TargetOracle,
) : RunFunction {
    override val name: String = RunFunctions.REGISTER_OWNER

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            val identity = runtime.identity
            if (identity.registration != RegistrationMode.OWNER) {
                throw RunFailure(
                    FailureReason.MISSING_PREREQUISITE,
                    "register_owner is for the company owner; ${identity.agentId} joins by ${identity.registration.key}.",
                )
            }
            val company = args[ARG_COMPANY]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_COMPANY
            val progress = FlowProgress()
            flows.run(this, FlowNames.REGISTER_OWNER, progress, FailureReason.REGISTRATION_FAILED, company)
            val published = publishCompany()
            flows.completeSignIn(this, progress, company = company)
            val shown = progress.identityShown ?: "The session was checked"
            succeeded("Registered ${identity.email} as owner of '$company'. $shown.${published.note}", objectId = published.company?.id)
        }

    /** What the test API said about the new company; [note] is appended to the summary. */
    private class Publication(
        val company: TestCompany?,
        val note: String,
    )

    /**
     * Publishes the new company for the other testers. The sign-up itself already succeeded at this point, so a test
     * API that lags or fails does not fail it: the failed lookup is recorded, the summary says so, and `seed_company`
     * looks the company up again.
     */
    private suspend fun RunTrace.publishCompany(): Publication {
        if (!oracle.isAvailable) return Publication(null, "")
        val email = runtime.identity.email
        val company =
            try {
                lookup("look up the company owned by $email") { targetFlows.retryOracle { oracle.companyByOwner(email) } }
            } catch (e: OracleException) {
                return Publication(null, " The company was not published: the test API failed (${e.message}).")
            } ?: return Publication(null, " The company was not published: the test API does not know it yet.")
        runtime.shared.put(SharedRunState.COMPANY_ID, company.id)
        company.code?.let { runtime.shared.put(SharedRunState.COMPANY_CODE, it) }
        return Publication(company, " Company id ${company.id}.")
    }

    companion object {
        const val ARG_COMPANY = "company"
        const val DEFAULT_COMPANY = "Pətək Test MMC"
    }
}
