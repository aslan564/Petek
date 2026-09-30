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
import az.petek.reporting.domain.ReportPdfPrinter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * A run's report as a PDF (`report/report.pdf`, Faza 12; the owner's decision of 2026-09-30), made when it is asked for
 * (the panel's "PDF yüklə", `petek report --pdf`), not with every run: [printer] prints the report's single-file page
 * ([source], `share.html`, whose screenshots are inside it), and prints it again only when that page was written
 * since. One print at a time, so two downloads of the same report never print it twice at once.
 */
class ExportReportPdfUseCase(
    private val artifacts: ArtifactStore,
    private val printer: ReportPdfPrinter,
    private val source: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** The PDF of [runId]'s report, printed now when it is missing or older than the page; null without a written report. */
    suspend fun export(runId: RunId): Path? =
        mutex.withLock {
            val directory = ReportLayout.directory(artifacts, runId)
            val html = directory.resolve(source)
            val pdf = directory.resolve(ReportLayout.PDF)
            val state = withContext(io) { stateOf(html, pdf) }
            if (state == State.NO_REPORT) return@withLock null
            if (state == State.STALE) printer.print(html, pdf)
            pdf
        }

    private fun stateOf(
        html: Path,
        pdf: Path,
    ): State =
        when {
            !Files.isRegularFile(html) -> State.NO_REPORT
            Files.isRegularFile(pdf) && Files.getLastModifiedTime(pdf) >= Files.getLastModifiedTime(html) -> State.FRESH
            else -> State.STALE
        }

    private enum class State { NO_REPORT, FRESH, STALE }
}
