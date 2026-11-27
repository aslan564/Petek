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

package az.petek.browser.infrastructure

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Real Chromium prints a self-contained page to a PDF, offline. */
class PlaywrightPdfPrinterTest {
    @TempDir
    lateinit var dir: Path

    private val site = TestSite()

    @AfterEach
    fun stop() = site.close()

    @Test
    fun `a page is printed to a PDF without the browser asking any address it names`() =
        runBlocking<Unit> {
            val html =
                dir.resolve("share.html").also {
                    Files.writeString(
                        it,
                        """
                        <!DOCTYPE html><html lang="az"><head><meta charset="utf-8"><title>Hesabat</title></head>
                        <body><h1>Pətək hesabatı: ə, ş, ğ, ç, ö, ü, ı</h1>
                        <img src="${site.baseUrl}/" alt="hədəfin ünvanı"><p>Nəticə: keçdi</p></body></html>
                        """.trimIndent(),
                    )
                }
            val pdf = dir.resolve("report.pdf")

            PlaywrightPdfPrinter().print(html, pdf)

            val bytes = Files.readAllBytes(pdf)
            String(bytes, 0, PDF_MAGIC.length, Charsets.US_ASCII) shouldBe PDF_MAGIC
            bytes.size shouldBeGreaterThan 1_000
            site.homeRequests.get() shouldBe 0
            Files.list(dir).use { files -> files.map { it.fileName.toString() }.sorted().toList() } shouldContainExactly
                listOf("report.pdf", "share.html")
        }

    private companion object {
        const val PDF_MAGIC = "%PDF-"
    }
}
