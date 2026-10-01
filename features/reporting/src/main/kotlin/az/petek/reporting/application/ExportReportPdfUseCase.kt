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
import java.nio.file.attribute.FileTime

/**
 * A run's report as a PDF (`report/report.pdf`, Faza 12; the owner's decision of 2026-09-30), made when it is asked for
 * (the panel's "PDF yüklə", `petek report --pdf`), not with every run: [printer] prints the report's single-file page
 * ([source], `share.html`, whose screenshots are inside it), and prints it again only when that page was written
 * since. The PDF carries the modification time the page had when its print began, and is fresh only while the page still
 * has that time: a page written again during a print (by another process) is printed again at the next export. One
 * print at a time in this process, so two downloads of the same report never print it twice at once.
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
            if (state == State.NoReport) return@withLock null
            if (state is State.Stale) {
                printer.print(html, pdf)
                withContext(io) { Files.setLastModifiedTime(pdf, state.page) }
            }
            pdf
        }

    private fun stateOf(
        html: Path,
        pdf: Path,
    ): State {
        if (!Files.isRegularFile(html)) return State.NoReport
        val page = Files.getLastModifiedTime(html)
        return if (Files.isRegularFile(pdf) && Files.getLastModifiedTime(pdf) == page) State.Fresh else State.Stale(page)
    }

    private sealed interface State {
        data object NoReport : State

        data object Fresh : State

        /** To be printed; [page] is the page's modification time the PDF will carry. */
        data class Stale(
            val page: FileTime,
        ) : State
    }
}
