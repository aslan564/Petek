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
