/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.core.ids.CorrelationId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord

/**
 * Judges step records in the context of the run they belong to, for the outcomes a test expects although a record on
 * its own looks like a failure:
 * - an expected refusal ([FailureKeys.isExpectedRefusal]): a forbidden action the target refused, recorded BLOCKED
 *   with `permission_denied` by the orchestrator. The agent's own records of that action (same correlation id) are
 *   expected too: in a step whose assertions test the refusal the agent may have reported it as a problem, and the
 *   orchestrator has already judged that the expected outcome;
 * - a lost race: in a `parallel` step with `only_one_succeeds`, every actor but the winner is refused or finds the
 *   object already decided. The orchestrator records such an action PASSED with detail `lost_race: ...`
 *   ([FailureKeys.isLostRace]), but the agent's own records of the same action (its turns, sharing the action's
 *   correlation id) may say FAILED ("already decided"). Those action records ([StepKind.DO] and [StepKind.RUN]) are
 *   expected too; the group assertion decides the race. Waits, emits and verification errors of the same actor are
 *   judged on their own.
 *
 * Findings, failed agents, the summary and stability all ask this one place, so they always agree.
 */
class ExpectedOutcomes(
    steps: List<StepRecord>,
) {
    private val lostRaces: Set<CorrelationId> = steps.filter(FailureKeys::isLostRace).mapTo(HashSet()) { it.correlationId }
    private val refusals: Set<CorrelationId> = steps.filter(FailureKeys::isExpectedRefusal).mapTo(HashSet()) { it.correlationId }

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
    fun isFailure(step: StepRecord): Boolean = FailureKeys.isFailure(step) && !isLostRace(step) && !isExpectedRefusal(step)

    /** Like [FailureKeys.of], but null for every part of a lost race or of an expected refusal. */
    fun failureKey(step: StepRecord): String? = if (isLostRace(step) || isExpectedRefusal(step)) null else FailureKeys.of(step)

    /** The records a report row should show as a lost race: the orchestrator's own and the agent's failing ones. */
    fun showsLostRace(step: StepRecord): Boolean =
        isLostRace(step) && (FailureKeys.isLostRace(step) || step.status in FailureKeys.FAILING_STATUSES)

    /** The records a report row should show as an expected refusal: the orchestrator's own and the agent's failing ones. */
    fun showsRefusal(step: StepRecord): Boolean =
        isExpectedRefusal(step) && (FailureKeys.isExpectedRefusal(step) || step.status in FailureKeys.FAILING_STATUSES)

    private companion object {
        val ACTION_KINDS = setOf(StepKind.DO, StepKind.RUN)
    }
}
