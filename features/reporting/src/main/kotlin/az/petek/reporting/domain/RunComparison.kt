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

import az.petek.core.error.PetekException
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.PageTimingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.isLookAction
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.VisualGate
import az.petek.reporting.domain.visual.VisualThresholds
import java.time.Duration

/**
 * What changed between two runs of one scenario (docs/PLAN.md Faza 14, the regression baseline; the owner put it first
 * on 2026-09-30): usually a run of the site's new release against a run of an earlier one, step by step.
 *
 * - Each step is judged in each run as the stability table judges it ([StepOutcomes]). Only what the site did is a
 *   change: a step a tester's agent or the surroundings lost in either run, or that either run never decided, is
 *   [StepChange.NOT_COMPARABLE], never a regression.
 * - Speed is compared only where the site sets it: the delivery of real-time events (t1 − t0, per event name), the
 *   deterministic `run` steps the site passed in both runs (per actor from the step's first to its last run record,
 *   the median over actors; a fixed or undecided step takes a time of its own, never a regression) and the
 *   pages' own timing as the browser measured it (`site_health`'s `perf`: load and largest contentful paint, the
 *   median over testers per page and screen; a layout shift that grew past 0.1 counts too). An AI step's time is
 *   mostly the AI thinking, so it is not compared.
 * - [scenarioChanged]: the two runs came from different campaign files (another hash). Steps are still matched by their
 *   ids; a step found in only one of them is then [StepChange.ADDED] or [StepChange.REMOVED].
 * - [looks]: how the pages looked (`site_health`'s `look`, docs/adr/0014), attached after the comparison by the
 *   reporting application. Under [VisualGate.REPORT] (the default) a changed look is shown, never a regression; under
 *   [VisualGate.FAIL] it counts as one. A look that is not comparable never does.
 */
data class RunComparison(
    val baseline: RunRecord,
    val current: RunRecord,
    val scenarioChanged: Boolean,
    val steps: List<StepComparison>,
    val deliveries: List<DeliveryComparison>,
    val thresholds: SpeedThresholds,
    val pages: List<PageComparison> = emptyList(),
    val looks: List<LookComparison> = emptyList(),
    val visualGate: VisualGate = VisualGate.REPORT,
    /** The thresholds [looks] were compared with; null when no look was compared. */
    val visualThresholds: VisualThresholds? = null,
) {
    val newFailures: List<StepComparison> get() = steps.filter { it.change == StepChange.NEW_FAILURE }
    val fixed: List<StepComparison> get() = steps.filter { it.change == StepChange.FIXED }
    val slowerSteps: List<StepComparison> get() = steps.filter { it.speed == SpeedChange.SLOWER }
    val slowerDeliveries: List<DeliveryComparison> get() = deliveries.filter { it.speed == SpeedChange.SLOWER }

    /** Pages that became usable later, or jumped more while loading. */
    val worsePages: List<PageComparison> get() = pages.filter { it.speed == SpeedChange.SLOWER || it.shiftGrew }

    /** Pages that look different now. */
    val changedLooks: List<LookComparison> get() = looks.filter { it.change == LookChange.CHANGED }

    /** Looks that could not be compared, each with its reason. */
    val incomparableLooks: List<LookComparison> get() = looks.filter { it.change == LookChange.NOT_COMPARABLE }

    /**
     * The later run is worse: a step the site passed before fails now, something the site sets got slower, or, under
     * [VisualGate.FAIL] only, a page looks different.
     */
    val regressed: Boolean
        get() =
            newFailures.isNotEmpty() || slowerSteps.isNotEmpty() || slowerDeliveries.isNotEmpty() || worsePages.isNotEmpty() ||
                (visualGate == VisualGate.FAIL && changedLooks.isNotEmpty())
}

/** How one step's result changed from the baseline to the current run. */
enum class StepChange {
    /** The site passed it before and fails it now: a regression. */
    NEW_FAILURE,

    /** The site failed it before and passes it now. */
    FIXED,

    /** The site fails it in both runs. */
    STILL_FAILING,

    /** The site fails it now; the baseline did not decide it (skipped, not reached, or its tester got lost). */
    FAILING,

    /** Passed in both runs. */
    UNCHANGED,

    /** One of the runs did not decide it: its tester's agent or the surroundings failed, or it was skipped or not reached. */
    NOT_COMPARABLE,

    /** Only in the current run, whose scenario changed. */
    ADDED,

    /** Only in the baseline, whose scenario changed. */
    REMOVED,
}

enum class SpeedChange { SLOWER, FASTER }

/** One scenario step in both runs; [beforeMs]/[afterMs] are its deterministic `run` time, null when it has none. */
data class StepComparison(
    val scenarioStep: String,
    val change: StepChange,
    val beforeMs: Long? = null,
    val afterMs: Long? = null,
    val speed: SpeedChange? = null,
)

/** The delivery of one kind of real-time event in both runs: median and p95 of t1 − t0 over every receiver. */
data class DeliveryComparison(
    val event: String,
    val beforeP50Ms: Long,
    val beforeP95Ms: Long,
    val afterP50Ms: Long,
    val afterP95Ms: Long,
    val speed: SpeedChange? = null,
)

/**
 * One page on one screen in both runs, as the browser timed it (medians over the testers that timed it): when it loaded
 * and when its main content showed (largest contentful paint), and how much it jumped while loading (layout shift).
 * [speed] is SLOWER when either time got slower ([SpeedThresholds]) and FASTER when one got faster and none slower;
 * [shiftGrew] when the shift passed 0.1 (the line under which a page counts as steady) and grew by 0.05 or more.
 */
data class PageComparison(
    val page: String,
    val device: String?,
    val beforeLoadMs: Long?,
    val afterLoadMs: Long?,
    val beforePaintMs: Long?,
    val afterPaintMs: Long?,
    val beforeShift: Double?,
    val afterShift: Double?,
    val speed: SpeedChange? = null,
    val shiftGrew: Boolean = false,
)

/**
 * When a time counts as changed: by more than [ratio] of the smaller one and by at least [atLeast], so noise of a few
 * milliseconds on a fast step is never a regression.
 */
data class SpeedThresholds(
    val ratio: Double = 0.25,
    val atLeast: Duration = Duration.ofMillis(300),
) {
    init {
        require(ratio > 0.0) { "ratio must be positive, was $ratio" }
        require(!atLeast.isNegative) { "atLeast must not be negative, was $atLeast" }
    }

    fun change(
        beforeMs: Long,
        afterMs: Long,
    ): SpeedChange? =
        when {
            afterMs - beforeMs >= atLeast.toMillis() && afterMs > beforeMs * (1 + ratio) -> SpeedChange.SLOWER
            beforeMs - afterMs >= atLeast.toMillis() && beforeMs > afterMs * (1 + ratio) -> SpeedChange.FASTER
            else -> null
        }
}

/** The evidence of one run that a comparison reads. */
data class ComparedEvidence(
    val run: RunRecord,
    val steps: List<StepRecord>,
    val assertions: List<AssertionRecord>,
    val events: List<EventRecord>,
    val receipts: List<EventReceipt>,
    val pageTimings: List<PageTimingRecord> = emptyList(),
)

/** Two runs that cannot be compared, and why ([reason], which callers say in their own words). */
class ComparisonRefusedException(
    val reason: Reason,
    message: String,
) : PetekException(message) {
    enum class Reason {
        /** No earlier finished run of the scenario (with the release asked for) to compare with. */
        NO_BASELINE,

        /** The runs come from differently named scenarios. */
        OTHER_SCENARIO,

        /** One of the runs has not finished. */
        NOT_FINISHED,

        /** A run compared with itself. */
        SAME_RUN,
    }
}

/**
 * Writes one format of a [RunComparison] into a report directory (the current run's) and returns the written file: one
 * file per pair of runs ([fileName]), so comparisons with different baselines never overwrite one another.
 */
interface ComparisonWriter {
    /** The file of [comparison]: `compare-<baseline run id>.<extension>`. */
    fun fileName(comparison: RunComparison): String

    fun write(
        comparison: RunComparison,
        directory: java.nio.file.Path,
    ): java.nio.file.Path
}

/** Compares two runs of one scenario (see [RunComparison]). Pure: every time was measured by the harness. */
class RunComparer(
    private val thresholds: SpeedThresholds = SpeedThresholds(),
) {
    fun compare(
        baseline: ComparedEvidence,
        current: ComparedEvidence,
    ): RunComparison {
        val scenarioChanged = baseline.run.campaignHash != current.run.campaignHash
        val before = StepOutcomes.of(baseline.steps, baseline.assertions)
        val after = StepOutcomes.of(current.steps, current.assertions)
        val beforeSteps = stepsOf(baseline)
        val afterSteps = stepsOf(current)
        val beforeTimes = runTimes(baseline.steps)
        val afterTimes = runTimes(current.steps)
        val order = LinkedHashSet(afterSteps).apply { addAll(beforeSteps) }
        val steps =
            order.map { step ->
                val change =
                    when {
                        step !in beforeSteps && scenarioChanged -> StepChange.ADDED
                        step !in afterSteps && scenarioChanged -> StepChange.REMOVED
                        else -> change(state(step, before, beforeSteps), state(step, after, afterSteps))
                    }
                val beforeMs = beforeTimes[step]
                val afterMs = afterTimes[step]
                // Only a step the site passed both times has comparable times: a fix or a lost tester takes its own time.
                val speed =
                    if (change == StepChange.UNCHANGED && beforeMs != null &&
                        afterMs != null
                    ) {
                        thresholds.change(beforeMs, afterMs)
                    } else {
                        null
                    }
                StepComparison(step, change, beforeMs, afterMs, speed)
            }
        return RunComparison(
            baseline.run,
            current.run,
            scenarioChanged,
            steps,
            deliveries(baseline, current),
            thresholds,
            pages(baseline.pageTimings, current.pageTimings),
        )
    }

    /** Per page and screen both runs timed, the medians over their testers (see [PageComparison]). */
    private fun pages(
        before: List<PageTimingRecord>,
        after: List<PageTimingRecord>,
    ): List<PageComparison> {
        val earlier = before.groupBy { it.page to it.device }
        return after.groupBy { it.page to it.device }.filterKeys { it in earlier }.map { (key, now) ->
            val then = earlier.getValue(key)
            val beforeLoad = medianOf(then.mapNotNull { it.loadMs })
            val afterLoad = medianOf(now.mapNotNull { it.loadMs })
            val beforePaint = medianOf(then.mapNotNull { it.largestPaintMs })
            val afterPaint = medianOf(now.mapNotNull { it.largestPaintMs })
            val beforeShift =
                then
                    .mapNotNull { it.layoutShift }
                    .takeIf { it.isNotEmpty() }
                    ?.sorted()
                    ?.let { it[(it.size - 1) / 2] }
            val afterShift =
                now
                    .mapNotNull { it.layoutShift }
                    .takeIf { it.isNotEmpty() }
                    ?.sorted()
                    ?.let { it[(it.size - 1) / 2] }
            val changes =
                listOfNotNull(
                    if (beforeLoad != null && afterLoad != null) thresholds.change(beforeLoad, afterLoad) else null,
                    if (beforePaint != null && afterPaint != null) thresholds.change(beforePaint, afterPaint) else null,
                )
            val speed =
                when {
                    SpeedChange.SLOWER in changes -> SpeedChange.SLOWER
                    SpeedChange.FASTER in changes -> SpeedChange.FASTER
                    else -> null
                }
            val shiftGrew =
                beforeShift != null && afterShift != null && afterShift > STEADY_SHIFT && afterShift - beforeShift >= SHIFT_GROWTH
            PageComparison(key.first, key.second, beforeLoad, afterLoad, beforePaint, afterPaint, beforeShift, afterShift, speed, shiftGrew)
        }
    }

    private fun medianOf(values: List<Long>): Long? = values.takeIf { it.isNotEmpty() }?.let(::median)

    /** How a step stood in one run. */
    private enum class State { PASSED, SITE_FAILED, UNDECIDED }

    private fun state(
        step: String,
        outcome: StepOutcomes.Outcome,
        present: Set<String>,
    ): State =
        when {
            step !in present -> State.UNDECIDED
            step in outcome.passed -> State.PASSED
            outcome.failed[step] == FailureCause.SITE -> State.SITE_FAILED
            else -> State.UNDECIDED
        }

    private fun change(
        before: State,
        after: State,
    ): StepChange =
        when (before to after) {
            State.PASSED to State.PASSED -> StepChange.UNCHANGED
            State.PASSED to State.SITE_FAILED -> StepChange.NEW_FAILURE
            State.SITE_FAILED to State.PASSED -> StepChange.FIXED
            State.SITE_FAILED to State.SITE_FAILED -> StepChange.STILL_FAILING
            State.UNDECIDED to State.SITE_FAILED -> StepChange.FAILING
            else -> StepChange.NOT_COMPARABLE
        }

    /** Every scenario step the run has any evidence of, in the order it first appears. */
    private fun stepsOf(evidence: ComparedEvidence): Set<String> =
        LinkedHashSet<String>().apply {
            evidence.steps.filter { it.kind != StepKind.SYSTEM }.mapTo(this) { it.scenarioStep }
            evidence.assertions.mapTo(this) { it.scenarioStep }
        }

    /**
     * Per scenario step, the median over actors of the time from the actor's first to its last `run` record, less the
     * actor's page looks: a look waits for the page to settle (a busy page makes it wait longer), which is Pətək's
     * time, not the site's answer.
     */
    private fun runTimes(steps: List<StepRecord>): Map<String, Long> =
        steps
            .filter { it.kind == StepKind.RUN && it.agentId != null }
            .groupBy { it.scenarioStep }
            .mapValues { (_, records) ->
                val perActor =
                    records.groupBy { it.agentId }.values.map { own ->
                        val span = Duration.between(own.minOf { it.startedAt }, own.maxOf { it.endedAt }).toMillis()
                        val looks = own.filter { isLookAction(it.action) }.sumOf { Duration.between(it.startedAt, it.endedAt).toMillis() }
                        (span - looks).coerceAtLeast(0)
                    }
                median(perActor)
            }

    /** Per event name (an object's id differs between runs), the delivery times of every receiver in both runs. */
    private fun deliveries(
        baseline: ComparedEvidence,
        current: ComparedEvidence,
    ): List<DeliveryComparison> {
        val before = latencies(baseline)
        val after = latencies(current)
        return after.keys.filter { it in before }.map { event ->
            val (beforeP50, beforeP95) = percentiles(before.getValue(event))
            val (afterP50, afterP95) = percentiles(after.getValue(event))
            DeliveryComparison(event, beforeP50, beforeP95, afterP50, afterP95, thresholds.change(beforeP95, afterP95))
        }
    }

    private fun latencies(evidence: ComparedEvidence): Map<String, List<Long>> {
        val names = evidence.events.associate { it.eventId to it.name }
        return evidence.receipts
            .filter { it.received && it.latencyMs != null }
            .groupBy({ names[it.eventId] }, { checkNotNull(it.latencyMs) })
            .filterKeys { it != null }
            .mapKeys { checkNotNull(it.key) }
            .filterValues { it.isNotEmpty() }
    }

    private fun percentiles(values: List<Long>): Pair<Long, Long> =
        checkNotNull(LatencyStatistics.nearestRank(values, MEDIAN)) to checkNotNull(LatencyStatistics.nearestRank(values, P95))

    private fun median(values: List<Long>): Long = checkNotNull(LatencyStatistics.nearestRank(values, MEDIAN))

    private companion object {
        const val MEDIAN = 50
        const val P95 = 95

        /** A page whose cumulative layout shift stays under this counts as steady. */
        const val STEADY_SHIFT = 0.1

        /** How much the shift must grow to count as worse. */
        const val SHIFT_GROWTH = 0.05
    }
}
