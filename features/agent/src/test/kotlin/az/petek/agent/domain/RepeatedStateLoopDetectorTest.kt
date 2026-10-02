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

package az.petek.agent.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class RepeatedStateLoopDetectorTest {
    private val detector = RepeatedStateLoopDetector()

    private fun onOnePage(vararg actions: AgentAction) = actions.map { detector.register(it, PAGE) }

    @Test
    fun `the third identical action on the same page is a loop`() {
        onOnePage(AgentAction.Click(3), AgentAction.Click(3), AgentAction.Click(3)) shouldContainExactly listOf(false, false, true)
    }

    @Test
    fun `the same click on pages that change is progress, not a loop`() {
        // Refs are numbered afresh on every page: "Next" can be [7] on page 1, 2 and 3 of a list.
        (1..5).map { detector.register(AgentAction.Click(7), "/tickets?page=$it") } shouldContainExactly List(5) { false }
    }

    @Test
    fun `a cycle between two actions on an unchanged page is a loop`() {
        onOnePage(
            AgentAction.Click(3),
            AgentAction.Click(4),
            AgentAction.Click(3),
            AgentAction.Click(4),
            AgentAction.Click(3),
        ) shouldContainExactly listOf(false, false, false, false, true)
    }

    @Test
    fun `only the recent decisions count`() {
        val filler = (10..14).map { AgentAction.Click(it) }
        onOnePage(AgentAction.Click(3), AgentAction.Click(3), *filler.toTypedArray(), AgentAction.Click(3)).last() shouldBe false
    }

    @Test
    fun `arguments are part of the action value`() {
        onOnePage(
            AgentAction.Type(2, "a", false),
            AgentAction.Type(2, "a", true),
            AgentAction.Type(2, "a", false),
        ) shouldContainExactly listOf(false, false, false)
    }

    @Test
    fun `waiting, reading and fetching a code repeatedly count as loops too`() {
        val wait = AgentAction.WaitText("Sabah 10:00", 10.seconds)
        onOnePage(wait, wait, wait).last() shouldBe true
        detector.reset()
        val read = AgentAction.ReadText("[data-testid=\"ticket-status\"]")
        onOnePage(read, read, read).last() shouldBe true
        detector.reset()
        onOnePage(AgentAction.GetEmailCode, AgentAction.GetEmailCode, AgentAction.GetEmailCode).last() shouldBe true
        detector.reset()
        onOnePage(AgentAction.GetPhoneCode, AgentAction.GetPhoneCode, AgentAction.GetPhoneCode).last() shouldBe true
        detector.reset()
        onOnePage(AgentAction.GetEmailCode, AgentAction.GetPhoneCode, AgentAction.GetEmailCode).last() shouldBe false
    }

    @Test
    fun `the loop keeps being reported while it continues`() {
        onOnePage(AgentAction.Click(1), AgentAction.Click(1), AgentAction.Click(1), AgentAction.Click(1)) shouldContainExactly
            listOf(false, false, true, true)
    }

    @Test
    fun `reset forgets the recent decisions`() {
        onOnePage(AgentAction.Click(1), AgentAction.Click(1))
        detector.reset()
        onOnePage(AgentAction.Click(1), AgentAction.Click(1)) shouldContainExactly listOf(false, false)
    }

    @Test
    fun `the threshold and window are configurable within limits`() {
        val strict = RepeatedStateLoopDetector(threshold = 2, window = 2)
        strict.register(AgentAction.Navigate("/x"), PAGE) shouldBe false
        strict.register(AgentAction.Navigate("/x"), PAGE) shouldBe true
        shouldThrow<IllegalArgumentException> { RepeatedStateLoopDetector(threshold = 1) }
        shouldThrow<IllegalArgumentException> { RepeatedStateLoopDetector(threshold = 3, window = 2) }
    }

    private companion object {
        const val PAGE = "https://staging.portal.test/tickets|42"
    }
}
