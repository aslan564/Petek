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

package az.petek.agent.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.campaign.domain.StepAction
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Dispatches scenario actions for one agent: `do` goes to the LLM [loop] (the instruction arrives already rendered
 * by the orchestrator), `run` to the deterministic function of that name, a step without action simply succeeds.
 *
 * An unexpected exception is turned into an ERROR outcome for this agent only, so one agent's failure never takes
 * the other agents of the run down; cancellation (watchdog, abort) is always propagated.
 */
class DefaultTesterAgent(
    override val runtime: AgentRuntime,
    private val loop: AgentLoop,
    private val registry: RunFunctionRegistry,
) : TesterAgent {
    override suspend fun perform(
        action: StepAction,
        step: StepContext,
    ): ActionOutcome =
        try {
            dispatch(action, step)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = runtime.redact("${e::class.simpleName}: ${e.message ?: "no message"}")
            logger.error { "${runtime.identity.agentId}/${step.scenarioStep}: unexpected failure: $message" }
            ActionOutcome(ActionStatus.ERROR, "Unexpected error: $message")
        }

    private suspend fun dispatch(
        action: StepAction,
        step: StepContext,
    ): ActionOutcome =
        when (action) {
            is StepAction.Do -> {
                loop.execute(runtime, action.instruction, step)
            }

            is StepAction.Run -> {
                registry[action.function]?.execute(runtime, action.args, step)
                    ?: ActionOutcome(
                        ActionStatus.FAILED,
                        "Unknown run function '${action.function}'. Known: ${registry.names.sorted().joinToString()}.",
                        failureReason = FailureReason.MISSING_PREREQUISITE,
                    )
            }

            StepAction.None -> {
                ActionOutcome(ActionStatus.SUCCEEDED, "Nothing to perform.")
            }
        }
}
