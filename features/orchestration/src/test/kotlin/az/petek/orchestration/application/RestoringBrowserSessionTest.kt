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

package az.petek.orchestration.application

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserContextLostException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.LookRequest
import az.petek.browser.domain.PageLook
import az.petek.browser.domain.PageTiming
import az.petek.browser.domain.Viewport
import az.petek.browser.testing.FakeBrowserSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** A crashed browser context comes back with the same identity and its storage state (docs/PLAN.md Faza 3). */
class RestoringBrowserSessionTest {
    private val first = FakeBrowserSession("a07").apply { failOn = { it.startsWith("clickSelector") } }
    private val opened = mutableListOf<FakeBrowserSession>()
    private val restored = mutableListOf<String>()

    /** What a session opened after a crash shows as its look. */
    private var restoredLook: PageLook? = null

    private fun session(maxRestores: Int = 2) =
        RestoringBrowserSession(
            initial = LosingContext(first),
            reopen = {
                FakeBrowserSession("a07").also {
                    it.pageLook = restoredLook
                    opened += it
                }
            },
            onRestored = { count, reason, url -> restored += "$count|$reason|$url" },
            maxRestores = maxRestores,
        )

    /** Plays a crashed page: the calls [FakeBrowserSession.failOn] names fail as a lost context. */
    private class LosingContext(
        val fake: FakeBrowserSession,
    ) : BrowserSession by fake {
        override suspend fun clickSelector(selector: String) {
            try {
                fake.clickSelector(selector)
            } catch (e: BrowserActionException) {
                throw BrowserContextLostException("Target crashed", e)
            }
        }

        override suspend fun look(request: LookRequest): PageLook? {
            try {
                return fake.look(request)
            } catch (e: BrowserActionException) {
                throw BrowserContextLostException("Target crashed", e)
            }
        }
    }

    private fun pageLook(path: String) =
        PageLook(
            shots = emptyList(),
            viewport = Viewport(375, 812),
            pageHeight = 2_310,
            landedPath = path,
            status = 200,
            renderer = "chromium 153.0; Linux amd64; headless",
            settled = true,
            unsettled = emptyList(),
            fonts = emptyList(),
            anchors = emptyList(),
            rejectedSelectors = emptyList(),
        )

    @Test
    fun `what the page reports about itself comes from the browser underneath, never the interface's empty default`() =
        runBlocking<Unit> {
            val timing = PageTiming(90, 300, 900, 1_200, 0.02)
            first.timing = timing

            session().pageTiming() shouldBe timing
            val look = pageLook("/tickets")
            first.pageLook = look
            session().look(LookRequest()) shouldBe look
        }

    @Test
    fun `a look is made again on the restored session`() =
        runBlocking<Unit> {
            val look = pageLook("/tickets/7")
            restoredLook = look
            first.lookFailure = BrowserActionException("Target crashed")
            val session = session()
            session.navigate("/tickets/7")
            val request = LookRequest(loads = 1)

            session.look(request) shouldBe look

            first.lookRequests shouldContainExactly listOf(request)
            opened.single().lookRequests shouldContainExactly listOf(request)
            opened.single().actions shouldContainExactly listOf("navigate /tickets/7")
            restored shouldContainExactly listOf("1|Target crashed|/tickets/7")
        }

    @Test
    fun `a lost context is replaced, the page opened again and the call made once more`() =
        runBlocking<Unit> {
            val session = session()
            session.navigate("/tickets/7")

            session.clickSelector("#approve")

            first.closed shouldBe true
            opened.single().actions shouldContainExactly listOf("navigate /tickets/7", "clickSelector #approve")
            restored shouldContainExactly listOf("1|Target crashed|/tickets/7")
            session.active shouldBe opened.single()
        }

    @Test
    fun `after the allowed restores the loss reaches the caller`() =
        runBlocking<Unit> {
            val session = session(maxRestores = 0)

            shouldThrow<BrowserContextLostException> { session.clickSelector("#approve") }
            opened shouldBe emptyList()
        }

    @Test
    fun `other failures pass through untouched`() =
        runBlocking<Unit> {
            val session = session()
            first.failOn = { it.startsWith("fillSelector") }

            val failure = shouldThrow<BrowserActionException> { session.fillSelector("#x", "y") }

            (failure is BrowserContextLostException) shouldBe false
            opened shouldBe emptyList()
        }
}
