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

package az.petek.app.diagnostics

import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.SessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

data class SmokeResult(
    val url: String,
    val title: String,
    /** Interactive elements in the snapshot an agent would see. */
    val elements: Int,
    val network: NetworkObservation,
    val screenshot: Path,
)

/**
 * `petek smoke`: proves the browser stack works against a target without touching it: open the page in Chromium,
 * read its title and snapshot, watch the network for [observationWindow] to detect live-update transports, and save
 * a screenshot to [screenshot]. No login, no form, no write.
 */
class SmokeCheck(
    private val browser: BrowserEngine,
    private val browserConfig: BrowserEngineConfig,
    private val screenshot: Path,
    private val observationWindow: Duration,
) {
    suspend fun run(target: URI): SmokeResult {
        val factory = browser.start(browserConfig)
        try {
            val session = factory.open(SessionOptions(label = "smoke", baseUrl = target))
            try {
                session.navigate(target.toString())
                delay(observationWindow)
                val snapshot = session.snapshot()
                val network = session.networkObservation()
                val png = session.screenshot()
                withContext(Dispatchers.IO) {
                    screenshot.toAbsolutePath().parent?.let(Files::createDirectories)
                    Files.write(screenshot, png)
                }
                return SmokeResult(snapshot.url, snapshot.title, snapshot.elements.size, network, screenshot)
            } finally {
                session.close()
            }
        } finally {
            browser.stop()
        }
    }
}
