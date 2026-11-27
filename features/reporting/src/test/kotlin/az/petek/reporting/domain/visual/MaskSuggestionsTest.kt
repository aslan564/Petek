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

package az.petek.reporting.domain.visual

import az.petek.evidence.domain.LookAnchor
import az.petek.evidence.domain.LookBox
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MaskSuggestionsTest {
    private fun region(
        box: LookBox,
        runContent: Boolean = false,
    ) = ChangedRegion(box, 100, 4, runContent)

    @Test
    fun `a region inside an element with a test id suggests that element's selector`() {
        val suggestions =
            MaskSuggestions.of(
                listOf(region(LookBox(20, 20, 40, 10))),
                listOf(LookAnchor("[data-testid=\"price\"]", LookBox(10, 10, 100, 40))),
            )

        suggestions shouldContainExactly listOf("'[data-testid=\"price\"]'")
    }

    @Test
    fun `the smallest enclosing anchor wins`() {
        val anchors =
            listOf(
                LookAnchor("#main", LookBox(0, 0, 400, 400)),
                LookAnchor("[data-testid=\"ticker\"]", LookBox(15, 15, 60, 20)),
                // Covers only half of the region: never suggested.
                LookAnchor("#half", LookBox(20, 20, 20, 10)),
            )

        MaskSuggestions.of(listOf(region(LookBox(20, 20, 40, 10))), anchors) shouldContainExactly listOf("'[data-testid=\"ticker\"]'")
        MaskSuggestions.of(listOf(region(LookBox(20, 20, 40, 10), runContent = true)), anchors).shouldBeEmpty()
    }

    @Test
    fun `suggestions are quoted for YAML`() {
        MaskSuggestions.quoted("[data-testid=\"it's\"]") shouldBe "'[data-testid=\"it''s\"]'"
        MaskSuggestions.of(listOf(region(LookBox(0, 0, 10, 10))), listOf(LookAnchor("#a'b", LookBox(0, 0, 10, 10)))) shouldContainExactly
            listOf("'#a''b'")
    }
}
