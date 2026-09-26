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

package az.petek.agent.application

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.DialogType
import az.petek.browser.testing.FakeBrowserSession
import az.petek.core.time.HarnessTimestamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

class DialogNotesTest {
    private val at = HarnessTimestamp(Instant.EPOCH, 0)

    @Test
    fun `no dialogs means no note`() {
        describeDialogs(emptyList()).shouldBeNull()
        withNote("OK: clicked.", null) shouldBe "OK: clicked."
    }

    @Test
    fun `dialogs are listed in order with their type and quoted message`() {
        val note =
            describeDialogs(
                listOf(DialogEvent(DialogType.CONFIRM, "Bileti silək?", at), DialogEvent(DialogType.ALERT, "Silindi\nuğurla", at)),
            )

        note shouldBe "Browser dialogs (accepted): confirm \"Bileti silək?\"; alert \"Silindi uğurla\"."
        withNote("OK: clicked.", note) shouldBe "OK: clicked. $note"
    }

    @Test
    fun `the note drains the session, so each dialog is noted once`() =
        runBlocking<Unit> {
            val session = FakeBrowserSession()
            session.openDialog(DialogType.PROMPT, "Adınız?")

            session.dialogNote() shouldBe "Browser dialogs (accepted): prompt \"Adınız?\"."
            session.dialogNote().shouldBeNull()
        }

    @Test
    fun `a session that fails to report dialogs gives no note instead of an error`() =
        runBlocking<Unit> {
            val broken =
                object : BrowserSession by FakeBrowserSession() {
                    override suspend fun drainDialogs(): List<DialogEvent> = throw BrowserActionException("closed")
                }

            broken.dialogNote().shouldBeNull()
        }

    @Test
    fun `cancellation is never swallowed`() =
        runBlocking<Unit> {
            val cancelled =
                object : BrowserSession by FakeBrowserSession() {
                    override suspend fun drainDialogs(): List<DialogEvent> = throw CancellationException("step timeout")
                }

            shouldThrow<CancellationException> { cancelled.dialogNote() }
        }
}
