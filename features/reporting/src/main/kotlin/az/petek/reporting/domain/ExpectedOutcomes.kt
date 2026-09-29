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

import az.petek.core.ids.CorrelationId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus

/**
 * Judges step records in the context of the run they belong to, for the outcomes a test expects although a record on
 * its own looks like a failure:
 * - an expected refusal ([FailureKeys.isExpectedRefusal]): a forbidden action the target refused, recorded BLOCKED
 *   with `permission_denied` by the orchestrator (one the target accepted instead is [FailureKeys.FORBIDDEN_ACCEPTED],
 *   a failure of the site). The agent's own records of that action (same correlation id) are
 *   expected too: in a step whose assertions test the refusal the agent may have reported it as a problem, and the
 *   orchestrator has already judged that the expected outcome;
 * - a lost race: in a `parallel` step with `only_one_succeeds`, every actor but the winner is refused or finds the
 *   object already decided. The orchestrator records such an action PASSED with detail `lost_race: ...`
 *   ([FailureKeys.isLostRace]), but the agent's own records of the same action (its turns, sharing the action's
 *   correlation id) may say FAILED ("already decided"). Those action records ([StepKind.DO] and [StepKind.RUN]) are
 *   expected too; the group assertion decides the race. Waits, emits and verification errors of the same actor are
 *   judged on their own;
 * - a recovered turn: one tool call of an action that went on to complete ([isRecovered]), such as a `select` whose
 *   error listed the options the agent then chose from, or a retried attempt of a run function. The action's own
 *   record ([isWholeAction]: its `do` task or `run` function, PASSED) is what the step did.
 *
 * Findings, failed agents, the summary and stability all ask this one place, so they always agree.
 */
class ExpectedOutcomes(
    steps: List<StepRecord>,
) {
    private val lostRaces: Set<CorrelationId> = steps.filter(FailureKeys::isLostRace).mapTo(HashSet()) { it.correlationId }
    private val refusals: Set<CorrelationId> = steps.filter(FailureKeys::isExpectedRefusal).mapTo(HashSet()) { it.correlationId }
    private val completed: Set<CorrelationId> =
        steps.filter { isWholeAction(it) && it.status == StepStatus.PASSED }.mapTo(HashSet()) { it.correlationId }

    /**
     * [step] is one turn of an action that went on to complete: a tool call the agent recovered from, or a retried
     * attempt of a run function. Not a failure of the step; the action's own PASSED record says what it did.
     */
    fun isRecovered(step: StepRecord): Boolean = step.kind in ACTION_KINDS && !isWholeAction(step) && step.correlationId in completed

    /** [step] records (part of) an action that lost a race. */
    fun isLostRace(step: StepRecord): Boolean = step.kind in ACTION_KINDS && step.correlationId in lostRaces

    /**
     * [step] records (part of) an action the target refused as the step expected: the orchestrator's `permission_denied`
     * record, or the agent's own turns of that action (the agent may have called the same refusal a problem).
     */
    fun isExpectedRefusal(step: StepRecord): Boolean =
        FailureKeys.isExpectedRefusal(step) || (step.kind in ACTION_KINDS && step.correlationId in refusals)

    /** [step] is an outcome the test expected: an expected refusal or part of a lost race. */
    fun isExpected(step: StepRecord): Boolean = isExpectedRefusal(step) || isLostRace(step)

    /** The action did not complete and that was not an expected outcome. */
    fun isFailure(step: StepRecord): Boolean =
        FailureKeys.isFailure(step) && !isLostRace(step) && !isExpectedRefusal(step) && !isRecovered(step)

    /** Like [FailureKeys.of], but null for every part of a lost race, of an expected refusal or of a completed action. */
    fun failureKey(step: StepRecord): String? =
        if (isLostRace(step) || isExpectedRefusal(step) || isRecovered(step)) null else FailureKeys.of(step)

    /** The records a report row should show as a lost race: the orchestrator's own and the agent's failing ones. */
    fun showsLostRace(step: StepRecord): Boolean =
        isLostRace(step) && (FailureKeys.isLostRace(step) || step.status in FailureKeys.FAILING_STATUSES)

    /** The records a report row should show as an expected refusal: the orchestrator's own and the agent's failing ones. */
    fun showsRefusal(step: StepRecord): Boolean =
        isExpectedRefusal(step) && (FailureKeys.isExpectedRefusal(step) || step.status in FailureKeys.FAILING_STATUSES)

    companion object {
        private val ACTION_KINDS = setOf(StepKind.DO, StepKind.RUN)
        private val WHOLE_ACTION = Regex("^(do: |run [a-z_]+$)")

        /** [step] is an actor's record of its whole action in a step: `do: <task>` or `run <function>`, not one turn. */
        fun isWholeAction(step: StepRecord): Boolean = step.kind in ACTION_KINDS && WHOLE_ACTION.containsMatchIn(step.action)
    }
}
