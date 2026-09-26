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

package az.petek.orchestration.application

import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.core.ids.AgentId
import az.petek.orchestration.domain.ActorResolver
import az.petek.orchestration.domain.PlannedActionKind
import az.petek.orchestration.domain.PlannedStep
import az.petek.orchestration.domain.RunPlan

/**
 * Builds a run's [RunPlan]: a step already run lists the agents that ran it ([RunState.executedActors]); every other
 * step resolves its actors against the identities still active, which is who will run it unless more agents fail.
 */
internal object RunPlans {
    fun of(
        run: RunState,
        resolver: ActorResolver,
    ): RunPlan {
        val active = run.activeIdentities()
        return RunPlan(
            runId = run.runId,
            steps =
                run.campaign.allSteps.map { step ->
                    planned(step, run.executedActors[step.id] ?: resolver.resolve(step.actors, active).map { it.agentId })
                },
        )
    }

    fun planned(
        step: ScenarioStep,
        agents: List<AgentId>,
    ): PlannedStep =
        PlannedStep(
            id = step.id,
            phase = step.phase,
            actorsRaw = step.actors.raw,
            actionKind = kindOf(step.action),
            actionText = textOf(step.action),
            emits = step.emits?.event,
            waitFor = step.waitFor?.event,
            parallel = step.parallel,
            assertionTypes = step.assertions.map { it.type },
            resolvedAgents = agents,
        )

    private fun kindOf(action: StepAction): PlannedActionKind =
        when (action) {
            is StepAction.Do -> PlannedActionKind.DO
            is StepAction.Run -> PlannedActionKind.RUN
            StepAction.None -> PlannedActionKind.NONE
        }

    private fun textOf(action: StepAction): String =
        when (action) {
            is StepAction.Do -> {
                action.instruction
            }

            is StepAction.Run -> {
                val args = action.args.entries.joinToString(", ") { "${it.key}=${it.value}" }
                if (args.isEmpty()) action.function else "${action.function} ($args)"
            }

            StepAction.None -> {
                ""
            }
        }
}
