/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
