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
import az.petek.agent.domain.StepContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** `logout`: signs out and waits until the current user is no longer shown. Signing out twice is harmless. */
internal class LogoutRunFunction(
    private val engine: RunEngine,
    private val settings: RunFunctionSettings,
) : RunFunction {
    override val name: String = RunFunctions.LOGOUT

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            if (!isVisible(TargetFlows.USER_NAME)) return@execute succeeded("Already signed out.")
            click("session.logout")
            val signedOut =
                probe("wait until signed out", { awaitSignedOut(this) }) { it }
            if (signedOut) {
                succeeded("Signed out.")
            } else {
                ActionOutcome(ActionStatus.FAILED, "Still signed in ${settings.uiTimeout} after clicking logout.")
            }
        }

    private suspend fun awaitSignedOut(trace: RunTrace): Boolean =
        withTimeoutOrNull(settings.uiTimeout) {
            while (trace.isVisible(TargetFlows.USER_NAME)) delay(settings.pollInterval)
            true
        } ?: false
}
