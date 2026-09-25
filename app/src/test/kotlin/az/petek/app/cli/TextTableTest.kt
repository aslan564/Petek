/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
