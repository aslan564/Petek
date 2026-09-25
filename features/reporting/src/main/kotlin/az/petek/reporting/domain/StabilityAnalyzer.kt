package az.petek.reporting.domain

import az.petek.evidence.domain.Verdict

/**
 * Compares the runs of one `--repeat` group step by step (docs/PLAN.md Faza 5). A scenario step passed in a run
 * when none of its assertions failed and none of its step records FAILED, ERRORED or was BLOCKED; SKIPPED
 * assertions are neutral, as in the judge. A step missing from a run (e.g. the run aborted before it) did not
 * pass there, so a step that passes only sometimes is [StabilityRow.flaky].
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
        val seen = run.steps.map { it.scenarioStep } + run.assertions.map { it.scenarioStep }
        val failed =
            run.steps.filter { it.status in FailureKeys.FAILING_STATUSES }.map { it.scenarioStep } +
                run.assertions.filter { it.verdict == Verdict.FAILED }.map { it.scenarioStep }
        return seen.toSet() - failed.toSet()
    }
}
