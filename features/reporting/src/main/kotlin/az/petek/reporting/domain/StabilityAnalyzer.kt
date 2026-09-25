/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict

/**
 * Compares the runs of one `--repeat` group step by step (docs/PLAN.md Faza 5). A scenario step passed in a run
 * when none of its assertions failed, none of its step records is a failure ([ExpectedOutcomes.isFailure]: FAILED,
 * ERROR or BLOCKED, except an expected refusal or a lost race), and something about it actually passed (a PASSED
 * step record or assertion). SKIPPED evidence is neutral, as in the judge, so a step that was only skipped in a run (no actors,
 * all of them excluded) did not pass there, just like a step missing from a run (e.g. the run aborted before it).
 * A step that passes only sometimes is therefore [StabilityRow.flaky].
 */
class StabilityAnalyzer {
    /** One row per scenario step in order of first appearance across [runs] (given in repeat order). */
    fun analyze(runs: List<RepeatRunEvidence>): List<StabilityRow> {
        val order = LinkedHashSet<String>()
        runs.forEach { run ->
            run.steps.mapTo(order) { it.scenarioStep }
            run.assertions.mapTo(order) { it.scenarioStep }
        }
        val passedPerRun = runs.map(::passedSteps)
        return order.map { step ->
            StabilityRow(scenarioStep = step, runs = runs.size, passed = passedPerRun.count { step in it })
        }
    }

    private fun passedSteps(run: RepeatRunEvidence): Set<String> {
        val expected = ExpectedOutcomes(run.steps)
        val passed =
            run.steps.filter { it.status == StepStatus.PASSED || expected.isExpected(it) }.map { it.scenarioStep } +
                run.assertions.filter { it.verdict == Verdict.PASSED }.map { it.scenarioStep }
        val failed =
            run.steps.filter(expected::isFailure).map { it.scenarioStep } +
                run.assertions.filter { it.verdict == Verdict.FAILED }.map { it.scenarioStep }
        return passed.toSet() - failed.toSet()
    }
}
