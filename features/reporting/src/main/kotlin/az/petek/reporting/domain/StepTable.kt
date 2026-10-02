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

import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.SKIP_ACTION
import az.petek.evidence.domain.SkipDetail
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UNCOVERED_ACTION
import az.petek.evidence.domain.WAVE_COVERAGE_ACTION

/** How many of a run's step rows passed and failed ([StepTable.counts]). */
data class StepCounts(
    val passed: Int,
    val failed: Int,
)

/**
 * The rows of a run's step table and the run's step counts: one definition for the report's table and summary, the
 * panel's run list and the end note of its test, so they never give one run two numbers.
 *
 * A row is a tester's action (`do`, `run`) or wait, and every record of a step nobody or somebody did not do: the roll
 * call's own (`not_reached`, `uncovered`), a wave's receivers no wave could serve (the FAILED `coverage` record), and a
 * tester left out of a step since an earlier failure (the runner's `skip`). A row passed when it is PASSED or its
 * outcome is the one the step expected (an expected refusal, a lost race), and failed when [ExpectedOutcomes.isFailure]
 * says so; a SKIPPED row is in neither count.
 */
object StepTable {
    private val ACTION_KINDS = setOf(StepKind.DO, StepKind.RUN, StepKind.WAIT)
    private val ROLL_CALL_ACTIONS = setOf(NOT_REACHED_ACTION, UNCOVERED_ACTION)

    fun isRow(step: StepRecord): Boolean =
        step.kind in ACTION_KINDS || step.action in ROLL_CALL_ACTIONS || isWaveGap(step) || isLeftOut(step)

    fun rows(steps: List<StepRecord>): List<StepRecord> = steps.filter(::isRow)

    /** The step counts of a run whose step records are [steps] (all of them: what is expected needs the whole run). */
    fun counts(steps: List<StepRecord>): StepCounts = counts(rows(steps), ExpectedOutcomes(steps))

    /** The step counts of [rows], judged with [expected] (made from all of the run's step records). */
    fun counts(
        rows: List<StepRecord>,
        expected: ExpectedOutcomes,
    ): StepCounts =
        StepCounts(
            passed = rows.count { it.status == StepStatus.PASSED || expected.isExpected(it) },
            failed = rows.count(expected::isFailure),
        )

    /** A wave's receivers that no wave could serve: the runner's agent-less FAILED `coverage` record (`not_covered`). */
    fun isWaveGap(step: StepRecord): Boolean =
        step.kind == StepKind.SYSTEM && step.agentId == null && step.action == WAVE_COVERAGE_ACTION && step.status == StepStatus.FAILED

    /** The runner's record of a tester out since an earlier failure in a step that began without it. */
    fun isLeftOut(step: StepRecord): Boolean =
        step.kind == StepKind.SYSTEM && step.agentId != null && step.action == SKIP_ACTION &&
            SkipDetail.failedEarlierReason(step.detail) != null
}
