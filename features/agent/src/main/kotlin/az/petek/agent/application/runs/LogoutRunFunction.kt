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
