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

package az.petek.reporting.domain

import az.petek.core.ids.AgentId
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict

/**
 * What one run did with each scenario step, the way the stability table and the release comparison judge it: a step
 * passed when none of its assertions failed, none of its step records is a failure ([ExpectedOutcomes.isFailure]:
 * FAILED, ERROR or BLOCKED, except an expected refusal or a lost race), and something about it actually passed (a
 * PASSED step record or assertion). SKIPPED and INCONCLUSIVE evidence is neutral, as in the judge: a step that was only
 * skipped, or never reached, is in neither [Outcome.passed] nor [Outcome.failed].
 *
 * A failed step is put on whom it failed on (Faza 24.13), per actor, then for the step as a whole the gravest: the site
 * (a failed check, a defect code saw, [FailureCause.SITE]) before the surroundings ([FailureCause.ENVIRONMENT]) before
 * the tester's agent ([FailureCause.AGENT]). An actor whose own action broke for its agent or the surroundings leaves
 * its failed checks moot: they only show that the action was not done.
 */
object StepOutcomes {
    /** The steps a run passed and, for the ones it failed, whom the failure is on. */
    class Outcome(
        val passed: Set<String>,
        val failed: Map<String, FailureCause>,
    )

    fun of(
        steps: List<StepRecord>,
        assertions: List<AssertionRecord>,
    ): Outcome {
        val expected = ExpectedOutcomes(steps)
        val failingSteps = steps.filter(expected::isFailure)
        val failedChecks = assertions.filter { it.verdict == Verdict.FAILED }
        val passed =
            steps.filter { it.status == StepStatus.PASSED || expected.isExpected(it) }.map { it.scenarioStep } +
                assertions.filter { it.verdict == Verdict.PASSED }.map { it.scenarioStep }
        val failed =
            (failingSteps.map { it.scenarioStep } + failedChecks.map { it.scenarioStep })
                .distinct()
                .associateWith { step ->
                    cause(failingSteps.filter { it.scenarioStep == step }, failedChecks.filter { it.scenarioStep == step })
                }
        return Outcome(passed.toSet() - failed.keys, failed)
    }

    /** Whom a step's failure in one run is on: the gravest cause among its actors (and its group checks). */
    private fun cause(
        steps: List<StepRecord>,
        checks: List<AssertionRecord>,
    ): FailureCause {
        val actors: Set<AgentId?> = steps.map { it.agentId }.toSet() + checks.map { it.agentId }
        return actors
            .map { actor -> actorCause(steps.filter { it.agentId == actor }, checks.any { it.agentId == actor }) }
            .minBy { GRAVITY.indexOf(it) }
    }

    private fun actorCause(
        failures: List<StepRecord>,
        failedCheck: Boolean,
    ): FailureCause {
        val causes = failures.map { FailureKeys.causeOf(FailureKeys.of(it)) }
        return when {
            FailureCause.SITE in causes -> FailureCause.SITE

            FailureCause.ENVIRONMENT in causes -> FailureCause.ENVIRONMENT

            // Its action broke: a check of that action only shows it was not done.
            causes.isNotEmpty() -> FailureCause.AGENT

            failedCheck -> FailureCause.SITE

            else -> FailureCause.AGENT
        }
    }

    /** Gravest first: what the site did outweighs the surroundings, which outweigh a lost agent. */
    private val GRAVITY = listOf(FailureCause.SITE, FailureCause.ENVIRONMENT, FailureCause.AGENT)
}
