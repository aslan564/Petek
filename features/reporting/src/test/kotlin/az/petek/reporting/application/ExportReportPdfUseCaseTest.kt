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
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.reporting.domain.ReportPdfPrinter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class ExportReportPdfUseCaseTest {
    @TempDir
    lateinit var dir: Path

    private val run = RunId("run_pdf")
    private val printed = mutableListOf<Pair<String, String>>()
    private val printer =
        ReportPdfPrinter { html, pdf ->
            printed += html.fileName.toString() to pdf.fileName.toString()
            Files.writeString(pdf, "%PDF-1.7 " + Files.readString(html))
        }

    private fun export() = ExportReportPdfUseCase(InMemoryArtifactStore(dir), printer, SHARE)

    private fun page(): Path =
        Files
            .createDirectories(dir.resolve(run.value).resolve(ReportLayout.DIRECTORY))
            .resolve(SHARE)
            .also { Files.writeString(it, "<html>hesabat</html>") }

    @Test
    fun `the report's single-file page is printed next to it, and only once while the page stays as it was`() =
        runBlocking<Unit> {
            page()
            val export = export()

            val pdf = export.export(run)

            pdf shouldBe dir.resolve("run_pdf/report/report.pdf")
            Files.readString(pdf!!) shouldBe "%PDF-1.7 <html>hesabat</html>"
            export.export(run) shouldBe pdf
            printed shouldContainExactly listOf(SHARE to ReportLayout.PDF)
        }

    @Test
    fun `a report written again after the PDF is printed again`() =
        runBlocking<Unit> {
            val html = page()
            val export = export()
            val pdf = export.export(run)!!
            Files.setLastModifiedTime(pdf, FileTime.from(Instant.parse("2026-09-30T10:00:00Z")))
            Files.writeString(html, "<html>yeni hesabat</html>")
            Files.setLastModifiedTime(html, FileTime.from(Instant.parse("2026-09-30T11:00:00Z")))

            export.export(run)

            Files.readString(pdf) shouldBe "%PDF-1.7 <html>yeni hesabat</html>"
            printed.size shouldBe 2
        }

    @Test
    fun `a run without a written report has no PDF and nothing is printed`() =
        runBlocking<Unit> {
            export().export(run).shouldBeNull()

            printed shouldBe emptyList()
        }

    private companion object {
        const val SHARE = "share.html"
    }
}
