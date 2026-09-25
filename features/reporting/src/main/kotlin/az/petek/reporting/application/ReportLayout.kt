package az.petek.reporting.application

import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactStore
import java.nio.file.Path

/**
 * Where a run's report lives: `<runDir>/report/`, next to the evidence it links to, so the report directory and
 * the run's artifacts can be moved or zipped together and every link stays a short relative path.
 */
object ReportLayout {
    const val DIRECTORY = "report"

    fun directory(
        artifacts: ArtifactStore,
        runId: RunId,
    ): Path = artifacts.runDirectory(runId).resolve(DIRECTORY)
}
