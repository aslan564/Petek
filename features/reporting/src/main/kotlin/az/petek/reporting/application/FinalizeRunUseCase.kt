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
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResult
import az.petek.reporting.domain.Judge
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportStore
import az.petek.reporting.domain.ReportWriter
import az.petek.reporting.domain.RunNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * The last stage of a run (docs/ARCHITECTURE.md "Finalize"): the judge turns the recorded evidence into findings,
 * the findings are stored, and every [writers] format is written into `<runDir>/report/`.
 *
 * Idempotent: a run that already has findings is not judged again, so `finalize` after a crash, or `petek report`
 * on a finished run, only rewrites the report; a run that has not finished yet is never judged into the store.
 * Calls are serialized so two concurrent finalizations of the same run cannot both record findings. A finding
 * without evidence of its own (a failed agent action) is linked to the artifacts of its step (CLAUDE.md rule 5).
 * Every writer is attempted even when another one fails; the failure is rethrown afterwards. The app adapts
 * [finalize] to orchestration's `RunFinalizer`.
 */
class FinalizeRunUseCase(
    private val runs: RunRepository,
    private val query: EvidenceQuery,
    private val recorder: EvidenceRecorder,
    private val artifacts: ArtifactStore,
    private val judge: Judge,
    private val builder: BuildReportUseCase,
    private val writers: List<ReportWriter>,
    /** Where the written report is kept for its readers; the run's own directory unless an edition says otherwise. */
    private val store: ReportStore = ReportStore.LOCAL,
    /** Writers do blocking file I/O. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    init {
        val names = writers.map { it.fileName }
        require(names.size == names.toSet().size) { "Report writers must write distinct files, got $names" }
    }

    /**
     * Judges (once) and writes the report of [runId]; returns the report directory. Findings are recorded only for a
     * finished run: a run that is still RUNNING (a `petek report` while it runs, or a crashed process) gets provisional
     * findings in its report but none in the store, so its real finalization still judges the complete evidence.
     */
    suspend fun finalize(runId: RunId): Path =
        mutex.withLock {
            val run = runs.find(runId) ?: throw RunNotFoundException(runId)
            val judged = if (query.findings(runId).isEmpty()) judge(run) else emptyList()
            val finished = run.result != RunResult.RUNNING
            if (finished) judged.forEach { recorder.finding(it) }
            val built = builder.build(runId)
            val model = if (finished || judged.isEmpty()) built else built.copy(findings = built.findings + judged)
            val directory = ReportLayout.directory(artifacts, runId)
            withContext(ioDispatcher) {
                Files.createDirectories(directory)
                writeAll(model, directory)
            }
            store.publish(runId, directory)
            directory
        }

    /** Every format is attempted, so one broken writer cannot cost the others; the first failure is then rethrown. */
    private fun writeAll(
        model: ReportModel,
        directory: Path,
    ) {
        val failures =
            writers.mapNotNull { writer ->
                try {
                    writer.write(model, directory)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private suspend fun judge(run: RunRecord): List<FindingRecord> {
        val evidenceByStep = query.artifacts(run.runId).groupBy { it.stepId }
        return judge
            .findings(run, query.assertions(run.runId), query.steps(run.runId))
            .map { it.withStepEvidence(evidenceByStep) }
    }

    private fun FindingRecord.withStepEvidence(evidenceByStep: Map<StepId, List<ArtifactRecord>>): FindingRecord {
        if (artifactIds.isNotEmpty() || stepId == null) return this
        return copy(artifactIds = evidenceByStep[stepId].orEmpty().map { it.artifactId })
    }
}
