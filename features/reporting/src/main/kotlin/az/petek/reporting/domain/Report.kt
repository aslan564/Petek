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

import az.petek.core.ids.RunId
import az.petek.core.ids.WorkspaceId
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.PageTimingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord
import java.net.URI
import java.nio.file.Path

/**
 * The three-source rule (docs/PLAN.md, design decision 4). A = what the sender did, B = what receivers saw,
 * C = what the target says (oracle). A=B=C -> pass; A≠C -> backend; C≠B -> delivery/UI; A≠B with no C -> investigate.
 */
data class Observation(
    val source: String,
    val value: String?,
    val passed: Boolean?,
)

sealed interface JudgeVerdict {
    data object Pass : JudgeVerdict

    data class Finding(
        val findingClass: FindingClass,
        val note: String,
    ) : JudgeVerdict
}

interface Judge {
    fun classify(
        a: Observation?,
        b: Observation?,
        c: Observation?,
    ): JudgeVerdict

    /** Groups a run's assertion records per scenario step and actor and derives findings. */
    fun findings(
        run: RunRecord,
        assertions: List<AssertionRecord>,
    ): List<FindingRecord>

    /**
     * Like [findings], and additionally turns failed agent actions in [steps] into findings, so a run that broke
     * before any assertion (e.g. `mail_timeout` during registration) still explains itself. The default ignores
     * [steps], which keeps judges written against the two-argument contract valid.
     */
    fun findings(
        run: RunRecord,
        assertions: List<AssertionRecord>,
        steps: List<StepRecord>,
    ): List<FindingRecord> = findings(run, assertions)
}

data class StepRow(
    val scenarioStep: String,
    val agentId: String?,
    val agentName: String?,
    /** [az.petek.evidence.domain.StepKind] name. */
    val kind: String,
    /** [az.petek.evidence.domain.StepStatus] name. */
    val status: String,
    val durationMs: Long,
    val detail: String?,
    /** Artifact id of the step's last screenshot; resolve it through [ReportModel.artifactLinks]. */
    val screenshot: String?,
    /** The row belongs to an action that lost a race: expected, shown as such (see [ExpectedOutcomes.showsLostRace]). */
    val lostRace: Boolean = false,
    /** The row belongs to an action the target refused as the step expected (see [ExpectedOutcomes.showsRefusal]). */
    val refused: Boolean = false,
)

data class LatencyStats(
    val event: String,
    val receivers: Int,
    val received: Int,
    val missing: List<String>,
    val avgMs: Long?,
    val p95Ms: Long?,
    val maxMs: Long?,
    val perReceiverMs: Map<String, Long?>,
)

/**
 * How one scenario step behaved across the runs of a `--repeat` group, with who each run that did not pass it was on
 * (Faza 24.13). A run can also leave a step undecided (skipped, not reached, only inconclusive checks), so the causes
 * need not add up to the runs that did not pass.
 */
data class StabilityRow(
    val scenarioStep: String,
    val runs: Int,
    val passed: Int,
    /** Runs in which the site failed the step: a check it failed, a defect code saw. */
    val siteFailures: Int = runs - passed,
    /** Runs in which the step failed because a tester's agent got lost (and only that). */
    val agentFailures: Int = 0,
    /** Runs in which the step failed because of the run's surroundings (inbox, shared IP, AI provider, browser). */
    val environmentFailures: Int = 0,
) {
    val passRate: Double get() = if (runs == 0) 0.0 else passed.toDouble() / runs

    /** Passed in some runs and the site failed it in others: the site itself is flaky here. */
    val flaky: Boolean get() = passed > 0 && siteFailures > 0

    /**
     * Passed in some runs and not in others, but never because of the site: the testers' agents, the surroundings or a
     * run that did not check it (skipped, not reached, inconclusive).
     */
    val unsteady: Boolean get() = passed in 1 until runs && siteFailures == 0

    /** Runs that neither passed nor failed the step: it was skipped, not reached, or its checks could not decide. */
    val undecided: Int get() = (runs - passed - siteFailures - agentFailures - environmentFailures).coerceAtLeast(0)
}

/**
 * One page on one screen as the run's testers timed it ([PageTimingRecord]): how many timed it
 * and the median of each measure; null where no tester's browser reported it.
 */
data class PageSpeedRow(
    val page: String,
    val device: String?,
    val testers: Int,
    val ttfbMs: Long?,
    val domContentLoadedMs: Long?,
    val loadMs: Long?,
    val largestPaintMs: Long?,
    val layoutShift: Double?,
) {
    companion object {
        /** Rows in the order the pages were first timed, each screen of a page together. */
        fun of(records: List<PageTimingRecord>): List<PageSpeedRow> =
            records.groupBy { it.page to it.device }.map { (key, own) ->
                fun median(values: List<Long>) = LatencyStatistics.nearestRank(values, MEDIAN)
                PageSpeedRow(
                    page = key.first,
                    device = key.second,
                    testers = own.map { it.agentId }.toSet().size,
                    ttfbMs = median(own.mapNotNull { it.ttfbMs }),
                    domContentLoadedMs = median(own.mapNotNull { it.domContentLoadedMs }),
                    loadMs = median(own.mapNotNull { it.loadMs }),
                    largestPaintMs = median(own.mapNotNull { it.largestPaintMs }),
                    layoutShift = own.mapNotNull { it.layoutShift }.sorted().let { shifts -> shifts.getOrNull((shifts.size - 1) / 2) },
                )
            }

        private const val MEDIAN = 50
    }
}

data class FailedAgentRow(
    val agentId: String,
    val name: String,
    val scenarioStep: String,
    val reason: String,
)

data class ReportSummary(
    val stepsPassed: Int,
    val stepsFailed: Int,
    val assertionsPassed: Int,
    val assertionsFailed: Int,
    val assertionsSkipped: Int,
    val agents: Int,
    val durationMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double?,
    val realtimeTransports: List<String>,
    /** Prompt tokens served from the provider's cache; most of a run's prompt when the provider caches, so shown too. */
    val cacheReadTokens: Long = 0,
    /** Oracle checks on a target without a test API: "N/A (no oracle)", a supported mode, not a skip. */
    val assertionsNotApplicable: Int = 0,
    /** Checks whose evidence could not decide them (Faza 24.12): neither passed nor a defect of the site. */
    val assertionsInconclusive: Int = 0,
)

/**
 * The run's roll call: who the run planned (its `roster` record), who acted, and every planned tester × step that has
 * no result of its own and why (the runner's `not_reached` records), every step nobody ran (`uncovered`), why the run
 * stopped early (`abort`) and whether it ran more testers at once than this machine is advised for (`capacity`). The
 * report says "every tester finished its steps" only when [complete]: nobody is left out silently, whatever N is. A run
 * whose roll call was never closed (killed before its end, still going, or recorded before roll calls) is never
 * [complete]: its report says the roll call is missing instead ([recorded]).
 */
data class RollCall(
    /** Testers the run planned, in order; empty for a run recorded before rosters were kept. */
    val planned: List<String> = emptyList(),
    /** Testers with at least one action of their own (a step, a check, an AI call). */
    val acted: List<String> = emptyList(),
    val notReached: List<NotReachedRow> = emptyList(),
    val uncovered: List<UncoveredRow> = emptyList(),
    /** Why the run stopped before its end, as recorded; null when it ran to its end. */
    val abortReason: String? = null,
    /** The machine's capacity record when the run was over it (`over_capacity: ...`); null otherwise. */
    val overCapacity: String? = null,
    /** The run concluded with its roster and a closed roll call (`roll_call`), so what is missing is known. */
    val recorded: Boolean = false,
) {
    /** Planned testers that never acted and have no step left open: the scenario gave them nothing to do. */
    val idle: List<String> get() = if (recorded) planned - acted.toSet() - notReached.map { it.agentId }.toSet() else emptyList()

    /** Planned testers with work and no step they did not get to; null when the roll call was not recorded. */
    val finished: Int? get() = if (recorded) (planned.toSet() - notReached.map { it.agentId }.toSet() - idle.toSet()).size else null

    /** Every planned tester got to every step it was given, every step had a tester, and the run was not stopped. */
    val complete: Boolean get() = recorded && notReached.isEmpty() && uncovered.isEmpty() && abortReason == null
}

/** A planned tester × step with no result of its own: [key] is `run_aborted`, `wave_not_started`, `failed_earlier`, `never_reached`. */
data class NotReachedRow(
    val agentId: String,
    val name: String,
    val scenarioStep: String,
    val key: String,
    val reason: String,
)

/** A scenario step no tester ran in any pass of the run, and why. */
data class UncoveredRow(
    val scenarioStep: String,
    val reason: String,
)

data class ReportModel(
    val run: RunRecord,
    val summary: ReportSummary,
    val steps: List<StepRow>,
    val assertions: List<AssertionRecord>,
    val latency: List<LatencyStats>,
    val findings: List<FindingRecord>,
    val failedAgents: List<FailedAgentRow>,
    /** Present when the run belongs to a `--repeat` group. */
    val stability: List<StabilityRow>?,
    /** Paths relative to the report directory, keyed by artifact id; an artifact that cannot be linked safely has none. */
    val artifactLinks: Map<String, String>,
    /** Token and cost accounting per agent, as recorded by the LLM metering (never estimated). */
    val usage: List<UsageRecord> = emptyList(),
    /** What the run's scenario left unchecked (its `coverage:` lines), which the summary names (2026-09-30). */
    val coverage: List<String> = emptyList(),
    /** How fast each page became usable, per screen, as the testers' browsers timed it (`site_health`'s `perf`). */
    val pageSpeed: List<PageSpeedRow> = emptyList(),
    /** Every planned tester set against who acted and who did not get to which step, and why ([RollCall]). */
    val rollCall: RollCall = RollCall(),
) {
    /** The workspace the run belongs to (ADR-0011); `local` on the owner's machine. */
    val workspaceId: WorkspaceId get() = run.workspaceId
}

/**
 * Where a written report is kept for its readers (ADR-0011 edition port): the open core keeps it in the run's own
 * report directory ([LOCAL]); a hosted edition may upload it and answer with a shared address.
 */
fun interface ReportStore {
    /** Keeps the report written into [directory] for [runId] and returns where it can be opened. */
    suspend fun publish(
        runId: RunId,
        directory: Path,
    ): URI

    companion object {
        /** The report stays where it was written; its HTML file is the address. */
        val LOCAL: ReportStore = ReportStore { _, directory -> directory.resolve(LOCAL_ENTRY).toUri() }

        const val LOCAL_ENTRY = "index.html"
    }
}

/**
 * Prints a report's self-contained HTML page ([html], every screenshot inside it) to a PDF file ([pdf]); the app gives
 * the browser's own print (the owner's decision of 2026-09-30).
 */
fun interface ReportPdfPrinter {
    suspend fun print(
        html: Path,
        pdf: Path,
    )
}

/** Writes one report format into [directory] and returns the written file. */
interface ReportWriter {
    val fileName: String

    fun write(
        model: ReportModel,
        directory: Path,
    ): Path
}
