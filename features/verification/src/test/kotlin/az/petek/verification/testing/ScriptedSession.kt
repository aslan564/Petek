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

package az.petek.verification.testing

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.WaitOutcome
import az.petek.browser.testing.FakeBrowserSession
import az.petek.core.testing.FakeHarnessClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * [FakeBrowserSession] plus the timing the assertions depend on: a text can appear some time after a wait starts
 * ([appearsAfter]); waits advance the [clock] by the time they would really take. Waits, immediate checks and
 * screenshots are counted, and [failure] / [screenshotFailure] make the matching calls throw.
 */
class ScriptedSession(
    val clock: FakeHarnessClock,
    val fake: FakeBrowserSession = FakeBrowserSession("a01", clock),
) : BrowserSession by fake {
    val appearsAfter = ConcurrentHashMap<String, Duration>()
    val waits = CopyOnWriteArrayList<Pair<String, Duration>>()
    val immediateChecks = CopyOnWriteArrayList<String>()
    val screenshots = AtomicInteger()

    @Volatile var failure: Exception? = null

    @Volatile var screenshotFailure: Exception? = null

    override suspend fun waitForText(
        text: String,
        timeout: Duration,
    ): WaitOutcome {
        failure?.let { throw it }
        waits += text to timeout
        val delay = appearsAfter[text] ?: if (text in fake.visibleTexts) Duration.ZERO else null
        return if (delay != null && delay <= timeout) {
            clock.advance(delay)
            WaitOutcome(true, clock.now())
        } else {
            clock.advance(timeout)
            WaitOutcome(false, null)
        }
    }

    override suspend fun isTextVisible(text: String): Boolean {
        failure?.let { throw it }
        immediateChecks += text
        return fake.isTextVisible(text)
    }

    override suspend fun isSelectorVisible(selector: String): Boolean {
        failure?.let { throw it }
        return fake.isSelectorVisible(selector)
    }

    override suspend fun count(selector: String): Int {
        failure?.let { throw it }
        return fake.count(selector)
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult {
        failure?.let { throw it }
        return fake.request(method, path, body)
    }

    override suspend fun screenshot(): ByteArray {
        screenshotFailure?.let { throw it }
        screenshots.incrementAndGet()
        return fake.screenshot()
    }
}
