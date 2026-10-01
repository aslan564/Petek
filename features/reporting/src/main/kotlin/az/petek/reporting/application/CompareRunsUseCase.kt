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

package az.petek.reporting.application

import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResult
import az.petek.reporting.domain.ComparedEvidence
import az.petek.reporting.domain.ComparisonRefusedException
import az.petek.reporting.domain.ComparisonRefusedException.Reason
import az.petek.reporting.domain.ComparisonWriter
import az.petek.reporting.domain.RunComparer
import az.petek.reporting.domain.RunComparison
import az.petek.reporting.domain.RunNotFoundException
import az.petek.reporting.domain.StepChange
import az.petek.reporting.domain.visual.VisualGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares a run with a baseline run of the same scenario (docs/PLAN.md Faza 14, the regression baseline) and writes
 * the comparison beside the run's report (`report/compare-<baseline run>.html` and `.md`, [writers]). The baseline is the
 * one asked for ([Baseline]): a named run, the latest run of a named release, or by default the latest earlier run of the
 * same scenario outside the run's own `--repeat` group. Only finished runs of one scenario (by name) are compared; any
 * other pair is a [ComparisonRefusedException] saying why.
 *
 * With [looks], the pages' looks (`site_health`'s `look`, docs/adr/0014) are compared too, each side with its repeat
 * siblings of the same scenario file as samples, and their pictures are derived under `report/visual/<baseline run>/`
 * ([Result.visualDirectory]). The [VisualGate] decides whether a changed look makes the comparison worse.
 */
class CompareRunsUseCase(
    private val runs: RunRepository,
    private val query: EvidenceQuery,
    private val artifacts: ArtifactStore,
    private val writers: List<ComparisonWriter> = emptyList(),
    private val comparer: RunComparer = RunComparer(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val looks: CompareLooks? = null,
) {
    /** Which run the current one is compared with. */
    sealed interface Baseline {
        /** The latest earlier finished run of the same scenario, outside the current run's repeat group. */
        data object Previous : Baseline

        data class Run(
            val runId: RunId,
        ) : Baseline

        /** The latest finished run of the same scenario that tested [label] (`petek run --release`). */
        data class Release(
            val label: String,
        ) : Baseline
    }

    /** A comparison and where its files were written; [visualDirectory] holds the looks' pictures, when any were compared. */
    data class Result(
        val comparison: RunComparison,
        val files: List<Path>,
        val visualDirectory: Path? = null,
    )

    /** [current] against [baseline], written beside [current]'s report; [visual] decides whether a changed look is worse. */
    suspend fun compare(
        current: RunId,
        baseline: Baseline = Baseline.Previous,
        visual: VisualGate = VisualGate.REPORT,
    ): Result {
        val now = runs.find(current) ?: throw RunNotFoundException(current)
        if (now.result ==
            RunResult.RUNNING
        ) {
            throw refused(Reason.NOT_FINISHED, "Run ${now.runId} has not finished yet; compare it once it has.")
        }
        val base = baselineOf(now, baseline)
        var comparison = comparer.compare(evidence(base), evidence(now)).copy(visualGate = visual)
        val directory = ReportLayout.directory(artifacts, now.runId)
        var visualDirectory: Path? = null
        if (looks != null) {
            val compared = lookInputs(base, now, comparison)?.let { looks.compare(it, directory) }.orEmpty()
            if (compared.isNotEmpty()) {
                comparison = comparison.copy(looks = compared, visualThresholds = looks.thresholds)
                visualDirectory = ReportLayout.visualDirectory(artifacts, now.runId, base.runId)
            }
        }
        val written = comparison
        val files =
            withContext(io) {
                Files.createDirectories(directory)
                writers.map { it.write(written, directory) }
            }
        return Result(comparison, files, visualDirectory)
    }

    /** Both runs' looks with their repeat siblings', or null when neither compared run looked at any page. */
    private suspend fun lookInputs(
        base: RunRecord,
        now: RunRecord,
        comparison: RunComparison,
    ): CompareLooks.Inputs? {
        val before = lookRun(base)
        val after = lookRun(now)
        if (before.looks.isEmpty() && after.looks.isEmpty()) return null
        return CompareLooks.Inputs(
            baseline = listOf(before) + siblings(base, now).map { lookRun(it) },
            current = listOf(after) + siblings(now, base).map { lookRun(it) },
            added = comparison.steps.filter { it.change == StepChange.ADDED }.mapTo(HashSet()) { it.scenarioStep },
            removed = comparison.steps.filter { it.change == StepChange.REMOVED }.mapTo(HashSet()) { it.scenarioStep },
        )
    }

    /** The finished runs of [run]'s repeat group from the same scenario file, oldest first, never [other]. */
    private suspend fun siblings(
        run: RunRecord,
        other: RunRecord,
    ): List<RunRecord> =
        run.repeatGroup
            ?.let { runs.byRepeatGroup(it) }
            .orEmpty()
            .filter {
                it.runId != run.runId && it.runId != other.runId && it.result != RunResult.RUNNING && it.campaignHash == run.campaignHash
            }.sortedBy { it.startedAt }

    /** A run's looks and the frames they name (a run without looks reads no artifacts). */
    private suspend fun lookRun(run: RunRecord): CompareLooks.LookRun {
        val looks = query.pageLooks(run.runId)
        val frames = if (looks.isEmpty()) emptyList() else query.artifacts(run.runId).filter { it.type == ArtifactType.VISUAL }
        return CompareLooks.LookRun(run, looks, frames)
    }

    private suspend fun baselineOf(
        now: RunRecord,
        baseline: Baseline,
    ): RunRecord {
        val chosen =
            when (baseline) {
                is Baseline.Run -> {
                    runs.find(baseline.runId) ?: throw RunNotFoundException(baseline.runId)
                }

                Baseline.Previous -> {
                    runs.latestFinished(now.campaignName, now.runId, startedBefore = now.startedAt, outsideGroup = now.repeatGroup)
                        ?: throw refused(
                            Reason.NO_BASELINE,
                            "No earlier finished run of '${now.campaignName}' to compare ${now.runId} with.",
                        )
                }

                is Baseline.Release -> {
                    runs.latestFinished(now.campaignName, now.runId, release = baseline.label)
                        ?: throw refused(
                            Reason.NO_BASELINE,
                            "No finished run of '${now.campaignName}' tested release '${baseline.label}' (petek run --release).",
                        )
                }
            }
        when {
            chosen.runId == now.runId -> {
                throw refused(Reason.SAME_RUN, "A run is not compared with itself; name an earlier run or release.")
            }

            chosen.campaignName != now.campaignName -> {
                throw refused(
                    Reason.OTHER_SCENARIO,
                    "${chosen.runId} ran '${chosen.campaignName}' and ${now.runId} ran '${now.campaignName}': only runs of one " +
                        "scenario are compared.",
                )
            }

            chosen.result == RunResult.RUNNING -> {
                throw refused(Reason.NOT_FINISHED, "Run ${chosen.runId} has not finished yet; compare with it once it has.")
            }
        }
        return chosen
    }

    private suspend fun evidence(run: RunRecord): ComparedEvidence =
        ComparedEvidence(
            run,
            query.steps(run.runId),
            query.assertions(run.runId),
            query.events(run.runId),
            query.receipts(run.runId),
            query.pageTimings(run.runId),
        )

    private fun refused(
        reason: Reason,
        message: String,
    ) = ComparisonRefusedException(reason, message)
}
