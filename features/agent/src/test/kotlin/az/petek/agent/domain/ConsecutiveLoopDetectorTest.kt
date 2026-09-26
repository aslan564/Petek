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

class ConsecutiveLoopDetectorTest {
    private val detector = ConsecutiveLoopDetector()

    private fun registerAll(vararg actions: AgentAction) = actions.map { detector.register(it) }

    @Test
    fun `the third identical action in a row is a loop`() {
        registerAll(AgentAction.Click(3), AgentAction.Click(3), AgentAction.Click(3)) shouldContainExactly listOf(false, false, true)
    }

    @Test
    fun `a different action in between breaks the streak`() {
        registerAll(
            AgentAction.Click(3),
            AgentAction.Click(3),
            AgentAction.Click(4),
            AgentAction.Click(3),
            AgentAction.Click(3),
        ) shouldContainExactly listOf(false, false, false, false, false)
    }

    @Test
    fun `arguments are part of the action value`() {
        registerAll(
            AgentAction.Type(2, "a", false),
            AgentAction.Type(2, "a", true),
            AgentAction.Type(2, "a", false),
        ) shouldContainExactly listOf(false, false, false)
    }

    @Test
    fun `waiting, reading and fetching a code repeatedly count as loops too`() {
        val wait = AgentAction.WaitText("Sabah 10:00", 10.seconds)
        registerAll(wait, wait, wait).last() shouldBe true
        detector.reset()
        val read = AgentAction.ReadText("[data-testid=\"ticket-status\"]")
        registerAll(read, read, read).last() shouldBe true
        detector.reset()
        registerAll(AgentAction.GetEmailCode, AgentAction.GetEmailCode, AgentAction.GetEmailCode).last() shouldBe true
        detector.reset()
        registerAll(AgentAction.GetPhoneCode, AgentAction.GetPhoneCode, AgentAction.GetPhoneCode).last() shouldBe true
        detector.reset()
        registerAll(AgentAction.GetEmailCode, AgentAction.GetPhoneCode, AgentAction.GetEmailCode).last() shouldBe false
    }

    @Test
    fun `the streak keeps reporting a loop while it continues`() {
        registerAll(AgentAction.Click(1), AgentAction.Click(1), AgentAction.Click(1), AgentAction.Click(1)) shouldContainExactly
            listOf(false, false, true, true)
    }

    @Test
    fun `reset forgets the streak`() {
        registerAll(AgentAction.Click(1), AgentAction.Click(1))
        detector.reset()
        registerAll(AgentAction.Click(1), AgentAction.Click(1)) shouldContainExactly listOf(false, false)
    }

    @Test
    fun `the threshold is configurable and must allow at least one repeat`() {
        val strict = ConsecutiveLoopDetector(threshold = 2)
        strict.register(AgentAction.Navigate("/x")) shouldBe false
        strict.register(AgentAction.Navigate("/x")) shouldBe true
        shouldThrow<IllegalArgumentException> { ConsecutiveLoopDetector(threshold = 1) }
    }
}
