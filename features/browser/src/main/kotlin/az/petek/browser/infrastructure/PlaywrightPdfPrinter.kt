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

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.HtmlPdfPrinter
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.Margin
import com.microsoft.playwright.options.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [HtmlPdfPrinter] with Chromium's own print (the report as a PDF, the owner's decision of 2026-09-30): a Playwright and
 * a headless Chromium of its own for each print, on a thread of their own (AGENTS.md rule 9), closed when it is done.
 * No new library: the same browser the testers use, so the PDF shows what the HTML report shows, Azerbaijani letters
 * and screenshots included. The document is set as the page's content, never opened from the disk, and printed offline:
 * every request it would make is refused, so a report that names the target never makes the browser ask it. Folded
 * sections (`<details>`) print open. A4, with backgrounds, in the light colour scheme; the file is written beside its
 * place first and moved there when complete.
 */
class PlaywrightPdfPrinter internal constructor(
    private val driver: PlaywrightDriver,
    private val loadTimeout: Duration,
) : HtmlPdfPrinter {
    constructor() : this(PlaywrightDriver(), 60.seconds)

    override suspend fun print(
        html: Path,
        pdf: Path,
    ) {
        val (document, partial) =
            withContext(Dispatchers.IO) {
                driver.installChromium()
                // A name of its own for each print: two processes printing one report never share the partial file.
                Files.readString(html) to Files.createTempFile(pdf.toAbsolutePath().parent, ".${pdf.fileName}.", ".part")
            }
        val thread = ConfinedThread("pdf-printer")
        try {
            thread.runToCompletion { printOn(document, partial) }
            withContext(Dispatchers.IO) {
                Files.move(partial, pdf, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (e: PlaywrightException) {
            throw BrowserActionException(
                "could not print ${html.fileName} as a PDF: ${PlaywrightFailures.reasonOf(e.message.orEmpty())}",
                e,
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                thread.close()
                Files.deleteIfExists(partial)
            }
        }
    }

    /** Runs on the printer's own thread: everything Playwright is created, used and closed here. */
    private fun printOn(
        document: String,
        target: Path,
    ) {
        Playwright.create().use { playwright ->
            val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
            try {
                val context = browser.newContext(Browser.NewContextOptions().setColorScheme(ColorScheme.LIGHT))
                context.route("**/*") { route -> route.abort() }
                val page = context.newPage()
                page.setContent(document, Page.SetContentOptions().setTimeout(loadTimeout.toPlaywrightTimeout()))
                // Folded sections print open: paper cannot be clicked.
                page.evaluate("document.querySelectorAll('details').forEach(d => { d.open = true; })")
                page.emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT))
                page.pdf(
                    Page
                        .PdfOptions()
                        .setPath(target)
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setMargin(
                            Margin()
                                .setTop(MARGIN)
                                .setBottom(MARGIN)
                                .setLeft(MARGIN)
                                .setRight(MARGIN),
                        ),
                )
            } finally {
                browser.close()
            }
        }
    }

    private companion object {
        const val MARGIN = "12mm"
    }
}
