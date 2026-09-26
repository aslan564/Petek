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

package az.petek.campaign.infrastructure

import az.petek.campaign.domain.SourceLines
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ActorFlowListsTest {
    @Test
    fun `bracketed actor items are quoted in place`() {
        quoteActorFlowList("    actor: [manager[IT], manager[HR]]") shouldBe """    actor: ["manager[IT]", "manager[HR]"]"""
    }

    @Test
    fun `a list item key and a trailing comment are kept`() {
        quoteActorFlowList("  - actor: [employee[dept=IT, n=1], admin]   # race") shouldBe
            """  - actor: ["employee[dept=IT, n=1]", "admin"]   # race"""
    }

    @Test
    fun `lists without brackets are already valid and stay untouched`() {
        quoteActorFlowList("    actor: [admin, employee]") shouldBe "    actor: [admin, employee]"
    }

    @Test
    fun `lists the author already quoted stay untouched`() {
        quoteActorFlowList("""    actor: ["manager[IT]", manager[HR]]""") shouldBe """    actor: ["manager[IT]", manager[HR]]"""
    }

    @Test
    fun `other keys and unbalanced or trailing text stay untouched`() {
        quoteActorFlowList("    departments: [IT[x]]") shouldBe "    departments: [IT[x]]"
        quoteActorFlowList("    actor: [manager[IT]") shouldBe "    actor: [manager[IT]"
        quoteActorFlowList("    actor: [manager[IT]] extra") shouldBe "    actor: [manager[IT]] extra"
        quoteActorFlowList("    actor: [manager[IT], ]") shouldBe "    actor: [manager[IT], ]"
        quoteActorFlowList("    actor: manager[IT]") shouldBe "    actor: manager[IT]"
    }

    @Test
    fun `backslashes are escaped inside the quotes`() {
        quoteActorFlowList("""actor: [a\b[x]]""") shouldBe """actor: ["a\\b[x]"]"""
    }

    @Test
    fun `changed lines are numbered from one`() {
        val text = "steps:\n  - actor: [manager[IT], manager[HR]]\n    do: x\n  - actor: [admin[x]]"
        changedLines(text, quoteActorFlowLists(text)) shouldBe setOf(2, 4)
        changedLines(text, text) shouldBe emptySet()
    }

    @Test
    fun `only step actor lines may be changed by the repair`() {
        val lines = SourceLines(mapOf("steps[0]" to 2, "steps[0].actor" to 2, "setup[1].actor" to 7, "steps[0].do" to 3))
        onlyStepActorsChanged(setOf(2, 7), lines) shouldBe true
        onlyStepActorsChanged(setOf(2, 4), lines) shouldBe false
        onlyStepActorsChanged(setOf(3), lines) shouldBe false
        onlyStepActorsChanged(emptySet(), lines) shouldBe true
    }

    @Test
    fun `the number of lines never changes`() {
        val text = "steps:\r\n  - actor: [manager[IT], manager[HR]]\r\n    do: x\n"
        val quoted = quoteActorFlowLists(text)
        quoted.lines().size shouldBe text.lines().size
        quoted shouldBe "steps:\r\n  - actor: [\"manager[IT]\", \"manager[HR]\"]\r\n    do: x\n"
    }
}
