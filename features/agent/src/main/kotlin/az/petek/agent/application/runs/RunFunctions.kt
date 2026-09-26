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

import az.petek.agent.application.RunFunctionRegistry
import az.petek.agent.application.StepEvidence
import az.petek.core.ids.IdGenerator
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.oracle.domain.TargetOracle

/** Names of the built-in deterministic `run` functions and the factory of their registry. */
object RunFunctions {
    const val LOGIN = "login"
    const val VERIFY_IDENTITY = "verify_identity"
    const val READ_EMAIL_CODE = "read_email_code"
    const val REGISTER_OWNER = "register_owner"
    const val SEED_COMPANY = "seed_company"
    const val REGISTER_AND_LOGIN = "register_and_login"
    const val LOGOUT = "logout"
    const val SITE_HEALTH = "site_health"
    const val DIRECT_URL = "direct_url"

    /** Every built-in name; the campaign validator accepts exactly these (plus any custom additions). */
    val NAMES: Set<String> =
        setOf(LOGIN, VERIFY_IDENTITY, READ_EMAIL_CODE, REGISTER_OWNER, SEED_COMPANY, REGISTER_AND_LOGIN, LOGOUT, SITE_HEALTH, DIRECT_URL)

    /**
     * Builds the registry with every built-in function. The functions share nothing but their stateless
     * collaborators, so one registry serves all agents of a run. The sign-up and sign-in functions execute the flows of
     * the campaign's target profile ([az.petek.campaign.domain.FlowNames]), so they work for any site the profile
     * describes; the defaults follow docs/TARGET_CONTRACT.md.
     */
    fun standard(
        oracle: TargetOracle,
        verification: AwaitVerificationUseCase,
        recorder: EvidenceRecorder,
        artifacts: ArtifactStore,
        clock: HarnessClock,
        ids: IdGenerator,
        settings: RunFunctionSettings = RunFunctionSettings(),
    ): RunFunctionRegistry {
        val engine = RunEngine(StepEvidence(recorder, artifacts, clock, ids))
        val flows = TargetFlows(verification, settings)
        val runner = FlowRunner(oracle, verification, flows, settings)
        return RunFunctionRegistry(
            listOf(
                LoginRunFunction(engine, runner),
                VerifyIdentityRunFunction(engine, runner),
                ReadEmailCodeRunFunction(engine, flows),
                RegisterOwnerRunFunction(engine, runner, flows, oracle),
                SeedCompanyRunFunction(engine, flows, oracle, settings),
                RegisterAndLoginRunFunction(engine, runner, settings),
                LogoutRunFunction(engine, settings),
                SiteHealthRunFunction(engine, runner),
                DirectUrlRunFunction(engine),
            ),
        )
    }
}
