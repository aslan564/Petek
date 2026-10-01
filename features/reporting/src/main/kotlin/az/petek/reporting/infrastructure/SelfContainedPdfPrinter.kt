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

package az.petek.reporting.infrastructure

import az.petek.reporting.domain.ReportPdfPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ReportPdfPrinter] that gives [browserPrint] (the browser's own print, offline) the report page with every screenshot of the
 * run inside it. `share.html` leaves a screenshot over its size cap as a link, which an offline print cannot load; such a
 * page is printed from a copy with those screenshots embedded too (written beside it, removed after the print). An image
 * outside the run's directory stays a link, as in `share.html`.
 */
class SelfContainedPdfPrinter(
    private val browserPrint: suspend (html: Path, pdf: Path) -> Unit,
) : ReportPdfPrinter {
    override suspend fun print(
        html: Path,
        pdf: Path,
    ) {
        val copy =
            withContext(Dispatchers.IO) {
                val page = Files.readString(html)
                val complete = ReportImages.embed(page, html.toAbsolutePath().parent, Long.MAX_VALUE)
                if (complete == page) {
                    null
                } else {
                    Files.createTempFile(html.toAbsolutePath().parent, ".print-", ".html").also { Files.writeString(it, complete) }
                }
            }
        try {
            browserPrint(copy ?: html, pdf)
        } finally {
            if (copy != null) withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(copy) }
        }
    }
}
