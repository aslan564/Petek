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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares a run with a baseline run of the same scenario (docs/PLAN.md Faza 14, the regression baseline) and writes
 * the comparison beside the run's report (`report/compare.html`, `report/compare.md`, [writers]). The baseline is the
 * one asked for ([Baseline]): a named run, the latest run of a named release, or by default the latest earlier run of the
 * same scenario outside the run's own `--repeat` group. Only finished runs of one scenario (by name) are compared; any
 * other pair is a [ComparisonRefusedException] saying why.
 */
class CompareRunsUseCase(
    private val runs: RunRepository,
    private val query: EvidenceQuery,
    private val artifacts: ArtifactStore,
    private val writers: List<ComparisonWriter> = emptyList(),
    private val comparer: RunComparer = RunComparer(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
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

    /** A comparison and where its files were written. */
    data class Result(
        val comparison: RunComparison,
        val files: List<Path>,
    )

    /** [current] against [baseline], written beside [current]'s report. */
    suspend fun compare(
        current: RunId,
        baseline: Baseline = Baseline.Previous,
    ): Result {
        val now = runs.find(current) ?: throw RunNotFoundException(current)
        if (now.result ==
            RunResult.RUNNING
        ) {
            throw refused(Reason.NOT_FINISHED, "Run ${now.runId} has not finished yet; compare it once it has.")
        }
        val base = baselineOf(now, baseline)
        val comparison = comparer.compare(evidence(base), evidence(now))
        val directory = ReportLayout.directory(artifacts, now.runId)
        val files =
            withContext(io) {
                Files.createDirectories(directory)
                writers.map { it.write(comparison, directory) }
            }
        return Result(comparison, files)
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
                    earlier(now).firstOrNull { now.repeatGroup == null || it.repeatGroup != now.repeatGroup }
                        ?: throw refused(
                            Reason.NO_BASELINE,
                            "No earlier finished run of '${now.campaignName}' to compare ${now.runId} with.",
                        )
                }

                is Baseline.Release -> {
                    earlier(now, before = false).firstOrNull { it.release == baseline.label }
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

    /** The finished runs of [now]'s scenario other than [now], newest first; with [before], only those started before it. */
    private suspend fun earlier(
        now: RunRecord,
        before: Boolean = true,
    ): List<RunRecord> =
        runs
            .byCampaign(now.campaignName, CANDIDATES)
            .filter { it.runId != now.runId && it.result != RunResult.RUNNING }
            .filter { !before || it.startedAt < now.startedAt }

    private suspend fun evidence(run: RunRecord): ComparedEvidence =
        ComparedEvidence(
            run,
            query.steps(run.runId),
            query.assertions(run.runId),
            query.events(run.runId),
            query.receipts(run.runId),
        )

    private fun refused(
        reason: Reason,
        message: String,
    ) = ComparisonRefusedException(reason, message)

    private companion object {
        /** How far back a baseline is looked for: the scenario's last runs. */
        const val CANDIDATES = 500
    }
}
