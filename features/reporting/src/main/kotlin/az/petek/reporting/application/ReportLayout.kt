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
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Where a run's report lives: `<runDir>/report/`, next to the evidence it links to, so the report directory and
 * the run's artifacts can be moved or zipped together and every link stays a short relative path.
 */
object ReportLayout {
    const val DIRECTORY = "report"

    /** The report as a PDF, printed on demand ([ExportReportPdfUseCase]); the HTML report links it by this name. */
    const val PDF = "report.pdf"

    /** Where a comparison's pictures are derived: `report/visual/<baseline run>/` (docs/adr/0014). */
    const val VISUAL = "visual"

    private val RUN_DIRECTORY = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

    fun directory(
        artifacts: ArtifactStore,
        runId: RunId,
    ): Path = artifacts.runDirectory(runId).resolve(DIRECTORY)

    /** The derived pictures and manifest of [current] compared with [baseline], inside [current]'s report. */
    fun visualDirectory(
        artifacts: ArtifactStore,
        current: RunId,
        baseline: RunId,
    ): Path = directory(artifacts, current).resolve(VISUAL).resolve(baseline.value)

    /**
     * The link from [reportRun]'s report directory to [record]'s file, relative (`../a01/0003-screenshot.png`, or
     * `../../<run>/a01/0003-visual.png` for another run's file). When the store cannot resolve the record, or its path
     * cannot be related to the report directory, the store's layout rule (`<runId>/<owner>/<file>`) is applied to the
     * recorded path; a recorded path that does not follow it (another run's directory, `..` segments) gets no link.
     */
    fun link(
        artifacts: ArtifactStore,
        reportRun: RunId,
        record: ArtifactRecord,
    ): String? =
        try {
            directory(artifacts, reportRun).normalize().relativize(artifacts.resolve(record).normalize()).invariantSeparatorsPathString
        } catch (_: IllegalArgumentException) {
            layoutLink(reportRun, record)
        }

    private fun layoutLink(
        reportRun: RunId,
        record: ArtifactRecord,
    ): String? {
        val path = record.relativePath.replace('\\', '/')
        val prefix = "${record.runId}/"
        if (!RUN_DIRECTORY.matches(record.runId.value) || !path.startsWith(prefix)) return null
        val inRun = path.removePrefix(prefix)
        val segments = inRun.split('/')
        return when {
            segments.any { it.isEmpty() || it == "." || it == ".." } -> null
            record.runId == reportRun -> "../$inRun"
            else -> "../../${record.runId}/$inRun"
        }
    }
}
