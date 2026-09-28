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
 * Compares the runs of one `--repeat` group step by step (docs/PLAN.md Faza 5). A scenario step passed in a run
 * when none of its assertions failed, none of its step records is a failure ([ExpectedOutcomes.isFailure]: FAILED,
 * ERROR or BLOCKED, except an expected refusal or a lost race), and something about it actually passed (a PASSED
 * step record or assertion). SKIPPED and INCONCLUSIVE evidence is neutral, as in the judge, so a step that was only
 * skipped in a run (no actors, all of them excluded) did not pass there, just like a step missing from a run (e.g. the
 * run aborted before it).
 *
 * A run that failed a step is put on whom it failed on (Faza 24.13), per actor, then for the step as a whole the
 * gravest: the site (a failed check, a defect code saw, [FailureCause.SITE]) before the surroundings
 * ([FailureCause.ENVIRONMENT]) before the tester's agent ([FailureCause.AGENT]). An actor whose own action broke for its
 * agent or the surroundings leaves its failed checks moot: they only show that the action was not done. So a step that
 * varies only because agents got lost is [StabilityRow.unsteady], never [StabilityRow.flaky], which is kept for a site
 * that fails a step it passes at other times.
 */
class StabilityAnalyzer {
    /** One row per scenario step in order of first appearance across [runs] (given in repeat order). */
    fun analyze(runs: List<RepeatRunEvidence>): List<StabilityRow> {
        val order = LinkedHashSet<String>()
        runs.forEach { run ->
            run.steps.mapTo(order) { it.scenarioStep }
            run.assertions.mapTo(order) { it.scenarioStep }
        }
        val outcomes = runs.map(::outcomes)
        return order.map { step ->
            val passed = outcomes.count { step in it.passed }
            val causes = outcomes.mapNotNull { it.failed[step] }
            StabilityRow(
                scenarioStep = step,
                runs = runs.size,
                passed = passed,
                siteFailures = causes.count { it == FailureCause.SITE },
                agentFailures = causes.count { it == FailureCause.AGENT },
                environmentFailures = causes.count { it == FailureCause.ENVIRONMENT },
            )
        }
    }

    /** What one run did with each step: the steps it passed and, for the ones it failed, whom the failure is on. */
    private class RunOutcome(
        val passed: Set<String>,
        val failed: Map<String, FailureCause>,
    )

    private fun outcomes(run: RepeatRunEvidence): RunOutcome {
        val expected = ExpectedOutcomes(run.steps)
        val failingSteps = run.steps.filter(expected::isFailure)
        val failedChecks = run.assertions.filter { it.verdict == Verdict.FAILED }
        val passed =
            run.steps.filter { it.status == StepStatus.PASSED || expected.isExpected(it) }.map { it.scenarioStep } +
                run.assertions.filter { it.verdict == Verdict.PASSED }.map { it.scenarioStep }
        val failed =
            (failingSteps.map { it.scenarioStep } + failedChecks.map { it.scenarioStep })
                .distinct()
                .associateWith { step ->
                    cause(
                        failingSteps.filter { it.scenarioStep == step },
                        failedChecks.filter {
                            it.scenarioStep ==
                                step
                        },
                    )
                }
        return RunOutcome(passed.toSet() - failed.keys, failed)
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

    private companion object {
        /** Gravest first: what the site did outweighs the surroundings, which outweigh a lost agent. */
        val GRAVITY = listOf(FailureCause.SITE, FailureCause.ENVIRONMENT, FailureCause.AGENT)
    }
}
