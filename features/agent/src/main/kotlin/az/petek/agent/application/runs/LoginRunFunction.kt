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
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.campaign.domain.FlowNames

/**
 * `login`: the target profile's `login` flow with the agent's own credentials, then the storage state saved for later
 * restores (unless the flow saved it itself). The contract's login flow also answers an e-mail or phone code the site
 * asks for on the way in.
 */
internal class LoginRunFunction(
    private val engine: RunEngine,
    private val flows: FlowRunner,
) : RunFunction {
    override val name: String = RunFunctions.LOGIN

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            val progress = FlowProgress()
            flows.run(this, FlowNames.LOGIN, progress, FailureReason.LOGIN_FAILED)
            if (!progress.sessionSaved) saveStorageState()
            succeeded("Logged in as ${runtime.identity.email}.")
        }
}
