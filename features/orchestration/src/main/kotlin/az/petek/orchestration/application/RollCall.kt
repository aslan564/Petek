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

import az.petek.core.ids.AgentId
import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.NotReached
import az.petek.evidence.domain.StepStatus
import az.petek.orchestration.domain.ActorResolver
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The roll call at a run's end ([DefaultCampaignRunner], "Nobody left out"): every tester each planned pass had for a
 * step (the actors it started with, or would have started with when it never began, and the testers out since an
 * earlier failure) that has no final record of its own for it gets a `not_reached` record saying why, so nobody
 * planned is missing from the evidence ([NotReached]).
 *
 * The record is SKIPPED when something the evidence already shows stopped the tester: the run's abort, its wave that
 * never began, its own earlier failure. A gap in a run that went on to its end (`never_reached`) is Pətək's own: its
 * record is FAILED and it counts as a failed step (with no agent, since the tester did nothing wrong), so the record
 * says what the verdict counts and such a run is never PASSED.
 *
 * Who was out is read when the step began: a step that began skipped its planned testers out by then with a record of
 * their own, and a tester that failed only later was never planned for it (with `n`, its place went to the next
 * tester). Only a step that never began names, at the end, the planned testers out by then.
 */
internal class RollCall(
    private val actors: ActorResolver,
    private val evidence: HarnessEvidence,
) {
    suspend fun call(run: RunState) {
        for (pass in run.passes) {
            for (planned in pass.steps) {
                val stepId = planned.step.id
                val chosen = run.chosenIn(pass.number, stepId)
                val acting =
                    chosen ?: actors.resolve(planned.step.actors, planned.pool.filterNot { run.isFailed(it.agentId) }).map { it.agentId }
                val failed =
                    if (chosen != null) {
                        emptyList()
                    } else {
                        actors.resolve(planned.step.actors, planned.pool).map { it.agentId }.filter(run::isFailed)
                    }
                (acting + failed)
                    .distinct()
                    .sorted()
                    .filterNot { run.isSettled(pass.number, stepId, it) }
                    .forEach { agentId -> missing(run, pass, stepId, agentId, acting = agentId in acting, began = chosen != null) }
            }
        }
    }

    private suspend fun missing(
        run: RunState,
        pass: PlannedPass,
        stepId: String,
        agentId: AgentId,
        acting: Boolean,
        began: Boolean,
    ) {
        val aborted = run.abortedBecause
        val waveNeverBegan = !began && pass.wave != null && !run.hasBegun(pass.number)
        val why =
            when {
                !acting -> {
                    NotReached.detail(NotReached.FAILED_EARLIER, run.failureReason(agentId) ?: "failed")
                }

                waveNeverBegan -> {
                    NotReached.detail(NotReached.WAVE_NOT_STARTED, "wave ${pass.wave} of ${pass.waves} never began; run aborted: $aborted")
                }

                aborted != null -> {
                    NotReached.detail(NotReached.RUN_ABORTED, aborted)
                }

                else -> {
                    NotReached.detail(NotReached.NEVER_REACHED, "the run went on, but $agentId has no record of step '$stepId'")
                }
            }
        if (aborted != null || !acting) {
            evidence.system(run, agentId, NOT_REACHED_ACTION, StepStatus.SKIPPED, why, stepId)
            return
        }
        logger.warn { "run ${run.runId}: $agentId has no record of step '$stepId' in pass ${pass.number}" }
        evidence.system(run, agentId, NOT_REACHED_ACTION, StepStatus.FAILED, why, stepId)
        run.tally.step(Tally.FAIL, null)
    }
}
