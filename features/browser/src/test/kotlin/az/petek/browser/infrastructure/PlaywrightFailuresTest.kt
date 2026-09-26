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
import az.petek.browser.domain.BrowserContextLostException
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.TimeoutError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.io.IOException

class PlaywrightFailuresTest {
    private val driverError =
        """
        Error {
          message='Timeout 300ms exceeded.
          name='TimeoutError
          stack='TimeoutError: Timeout 300ms exceeded.
            at _ProgressController.run (/tmp/playwright-java-1/package/lib/coreBundle.js:12360:32)
        }
        Call log:
        -   - waiting for locator("#missing")
        """.trimIndent()

    @Test
    fun `a driver error becomes its message and call log on one line`() {
        PlaywrightFailures.reasonOf(driverError) shouldBe "Timeout 300ms exceeded. (waiting for locator(\"#missing\"))"
    }

    @Test
    fun `a plain message is kept as it is`() {
        PlaywrightFailures.reasonOf("Target page, context or browser has been closed") shouldBe
            "Target page, context or browser has been closed"
        PlaywrightFailures.reasonOf("") shouldBe "unknown browser error"
    }

    @Test
    fun `a page, context or browser that is gone is a lost context the runner can restore`() {
        val lost =
            shouldThrow<BrowserContextLostException> {
                translatingFailures("click [3]") { throw PlaywrightException("Target page, context or browser has been closed") }
            }
        val crashed =
            shouldThrow<BrowserActionException> { translatingFailures("snapshot") { throw PlaywrightException("Target crashed") } }
        val ordinary = shouldThrow<BrowserActionException> { translatingFailures("click [3]") { throw TimeoutError(driverError) } }

        lost.message shouldBe "click [3] failed: Target page, context or browser has been closed"
        (crashed is BrowserContextLostException) shouldBe true
        (ordinary is BrowserContextLostException) shouldBe false
    }

    @Test
    fun `very long reasons are shortened`() {
        val reason = PlaywrightFailures.reasonOf("x".repeat(1_000))

        reason.length shouldBe 400
        reason shouldEndWith "…"
    }

    @Test
    fun `failures of an action are translated with the action name and the original cause`() {
        val original = TimeoutError(driverError)

        val failure = shouldThrow<BrowserActionException> { translatingFailures("click [3]") { throw original } }

        failure.message shouldBe "click [3] failed: Timeout 300ms exceeded. (waiting for locator(\"#missing\"))"
        failure.cause shouldBeSameInstanceAs original
    }

    @Test
    fun `typed text is masked and a cause that repeats it is dropped`() {
        val failure =
            shouldThrow<BrowserActionException> {
                translatingFailures("fill [2]", typedText = "hunter2") { throw PlaywrightException("cannot type hunter2 here") }
            }

        failure.message shouldBe "fill [2] failed: cannot type *** here"
        failure.cause.shouldBeNull()
    }

    @Test
    fun `input and output failures are translated too`() {
        val failure = shouldThrow<BrowserActionException> { translatingFailures("save storage state") { throw IOException("disk full") } }

        failure.message shouldBe "save storage state failed: disk full"
    }

    @Test
    fun `a browser action exception passes through unchanged`() {
        val original = BrowserActionException("element 9 not found, take a new snapshot")

        shouldThrow<BrowserActionException> { translatingFailures("click [9]") { throw original } } shouldBeSameInstanceAs original
    }
}
