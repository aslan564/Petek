/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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

    /** Every built-in name; the campaign validator accepts exactly these (plus any custom additions). */
    val NAMES: Set<String> =
        setOf(LOGIN, VERIFY_IDENTITY, READ_EMAIL_CODE, REGISTER_OWNER, SEED_COMPANY, REGISTER_AND_LOGIN, LOGOUT)

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
            ),
        )
    }
}
