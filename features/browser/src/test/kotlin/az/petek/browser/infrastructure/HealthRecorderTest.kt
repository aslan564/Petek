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

import az.petek.core.time.HarnessTimestamp
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class HealthRecorderTest {
    private val at = HarnessTimestamp(Instant.EPOCH, 0)
    private val recorder = HealthRecorder(URI("https://shop.test"))

    @Test
    fun `a resource that failed to load is named by its address, without its query`() {
        recorder.consoleError(
            "Failed to load resource: net::ERR_CERT_AUTHORITY_INVALID",
            at,
            "https://ads.example:8443/pixel.gif?uid=secret",
        )
        recorder.consoleError("TypeError: x is undefined", at, "https://shop.test/app.js")
        recorder.consoleError("Failed to load resource: the server responded with a status of 404 ()", at, null)

        recorder.since(at, 3.seconds) { it }.consoleErrors shouldContainExactly
            listOf(
                "Failed to load resource: net::ERR_CERT_AUTHORITY_INVALID (https://ads.example:8443/pixel.gif)",
                "TypeError: x is undefined",
                "Failed to load resource: the server responded with a status of 404 ()",
            )
    }
}
