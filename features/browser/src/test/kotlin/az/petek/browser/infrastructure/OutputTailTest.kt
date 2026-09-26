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

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OutputTailTest {
    @Test
    fun `only the last lines are kept`() {
        val tail = OutputTail(maxLines = 2)

        listOf("one", "two", "three").forEach(tail::add)

        tail.toString() shouldBe "two\nthree"
    }

    @Test
    fun `progress bar redraws keep only their final state and blank lines are skipped`() {
        val tail = OutputTail()

        tail.add("10%\r50%\r100%")
        tail.add("   ")

        tail.toString() shouldBe "100%"
    }

    @Test
    fun `an empty tail says so`() {
        OutputTail().toString() shouldBe "(no output)"
    }
}
