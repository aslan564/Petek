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

package az.petek.app.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TextTableTest {
    @Test
    fun `columns are as wide as their longest cell and nothing is cut`() {
        val table =
            TextTable.render(
                listOf("Agent", "E-mail"),
                listOf(listOf("a01", "eli.k7x2.a01@test.kadrohr.com"), listOf("a02", "x@y.z")),
            )

        table shouldBe
            """
            Agent  E-mail
            -----  -----------------------------
            a01    eli.k7x2.a01@test.kadrohr.com
            a02    x@y.z
            """.trimIndent()
    }

    @Test
    fun `line breaks inside a cell do not break the table`() {
        TextTable.render(listOf("Check", "Result"), listOf(listOf("Config", "one\n  two"))).lines().last() shouldBe "Config  one two"
    }

    @Test
    fun `missing cells are empty and decoration keeps the alignment`() {
        val bracketFirstColumn = { column: Int, cell: String -> if (column == 0) "[$cell]" else cell }

        val table = TextTable.render(listOf("", "Name"), listOf(listOf("✓"), listOf("✗", "b")), bracketFirstColumn)

        table.lines() shouldBe listOf("   Name", "-  ----", "[✓]", "[✗]  b")
    }
}
