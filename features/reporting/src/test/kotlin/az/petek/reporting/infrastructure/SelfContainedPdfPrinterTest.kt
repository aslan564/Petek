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

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SelfContainedPdfPrinterTest {
    @TempDir
    lateinit var dir: Path

    private val printed = mutableListOf<Pair<String, String>>()
    private val printer =
        SelfContainedPdfPrinter { html, pdf ->
            printed += html.fileName.toString() to Files.readString(html)
            Files.writeString(pdf, "%PDF-fake")
        }

    private fun report(page: String): Path {
        val report = Files.createDirectories(dir.resolve("run_1").resolve("report"))
        Files.createDirectories(dir.resolve("run_1").resolve("a01"))
        Files.write(dir.resolve("run_1/a01/0001-screenshot.png"), byteArrayOf(1, 2, 3))
        Files.write(dir.resolve("elsewhere.png"), byteArrayOf(4, 5, 6))
        return report.resolve("share.html").also { Files.writeString(it, page) }
    }

    @Test
    fun `a screenshot share_html left as a link is printed from inside the page, and the copy is removed`() =
        runBlocking<Unit> {
            val html = report("""<img src="../a01/0001-screenshot.png"><img src="../../elsewhere.png">""")

            printer.print(html, html.resolveSibling("report.pdf"))

            printed.single().second.let {
                it shouldContain "data:image/png;base64,AQID"
                it shouldContain "src=\"../../elsewhere.png\""
                it shouldNotContain "0001-screenshot.png"
            }
            Files.list(html.parent).use { files -> files.map { it.fileName.toString() }.sorted().toList() } shouldContainExactly
                listOf("report.pdf", "share.html")
        }

    @Test
    fun `a page with every screenshot already inside it is printed as it is`() =
        runBlocking<Unit> {
            val html = report("""<img src="data:image/png;base64,AQID"><p>hesabat</p>""")

            printer.print(html, html.resolveSibling("report.pdf"))

            printed.single() shouldBe ("share.html" to """<img src="data:image/png;base64,AQID"><p>hesabat</p>""")
        }
}
