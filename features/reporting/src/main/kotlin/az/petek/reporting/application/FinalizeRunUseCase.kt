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
import az.petek.reporting.domain.Judge
import az.petek.reporting.domain.ReportWriter
import az.petek.reporting.domain.RunNotFoundException
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
 * on a finished run, only rewrites the report. Calls are serialized so two concurrent finalizations of the same run
 * cannot both record findings. A finding without evidence of its own (a failed agent action) is linked to the
 * artifacts of its step (CLAUDE.md rule 5). The app adapts [finalize] to orchestration's `RunFinalizer`.
 */
class FinalizeRunUseCase(
    private val runs: RunRepository,
    private val query: EvidenceQuery,
    private val recorder: EvidenceRecorder,
    private val artifacts: ArtifactStore,
    private val judge: Judge,
    private val builder: BuildReportUseCase,
    private val writers: List<ReportWriter>,
    /** Writers do blocking file I/O. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    init {
        val names = writers.map { it.fileName }
        require(names.size == names.toSet().size) { "Report writers must write distinct files, got $names" }
    }

    /** Judges (once) and writes the report of [runId]; returns the report directory. */
    suspend fun finalize(runId: RunId): Path =
        mutex.withLock {
            val run = runs.find(runId) ?: throw RunNotFoundException(runId)
            if (query.findings(runId).isEmpty()) recordFindings(run)
            val model = builder.build(runId)
            val directory = ReportLayout.directory(artifacts, runId)
            withContext(ioDispatcher) {
                Files.createDirectories(directory)
                writers.forEach { it.write(model, directory) }
            }
            directory
        }

    private suspend fun recordFindings(run: RunRecord) {
        val evidenceByStep = query.artifacts(run.runId).groupBy { it.stepId }
        judge
            .findings(run, query.assertions(run.runId), query.steps(run.runId))
            .map { it.withStepEvidence(evidenceByStep) }
            .forEach { recorder.finding(it) }
    }

    private fun FindingRecord.withStepEvidence(evidenceByStep: Map<StepId, List<ArtifactRecord>>): FindingRecord {
        if (artifactIds.isNotEmpty() || stepId == null) return this
        return copy(artifactIds = evidenceByStep[stepId].orEmpty().map { it.artifactId })
    }
}
