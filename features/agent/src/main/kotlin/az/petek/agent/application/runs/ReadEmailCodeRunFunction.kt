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
import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.StepContext

/** `read_email_code`: waits for the newest verification code since the run started and stores it as `{vars.email_code}`. */
internal class ReadEmailCodeRunFunction(
    private val engine: RunEngine,
    private val flows: TargetFlows,
) : RunFunction {
    override val name: String = RunFunctions.READ_EMAIL_CODE

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            flows.awaitEmailCode(this)
            succeeded("Verification code stored as {vars.${AgentVariableKeys.EMAIL_CODE}}.")
        }
}
