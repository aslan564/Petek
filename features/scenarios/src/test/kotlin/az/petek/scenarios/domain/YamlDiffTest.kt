package az.petek.scenarios.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class YamlDiffTest {
    private fun lines(vararg lines: String): String = lines.joinToString("") { "$it\n" }

    private val ten = lines("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10")

    @Test
    fun `identical texts have no hunks and an empty unified diff`() {
        val diff = YamlDiff.of(ten, ten)

        diff.identical shouldBe true
        diff.hunks.shouldBeEmpty()
        diff.unified() shouldBe ""
        diff.added shouldBe 0
        diff.removed shouldBe 0
    }

    @Test
    fun `a changed line is shown with three lines of context and unified numbering`() {
        val diff = YamlDiff.of(ten, ten.replace("l5\n", "L5\n"), "mini v1", "mini v2")

        diff.unified() shouldBe
            """
            |--- mini v1
            |+++ mini v2
            |@@ -2,7 +2,7 @@
            | l2
            | l3
            | l4
            |-l5
            |+L5
            | l6
            | l7
            | l8
            |
            """.trimMargin()
        diff.added shouldBe 1
        diff.removed shouldBe 1
    }

    @Test
    fun `diff lines carry the line numbers of both texts`() {
        val hunk = YamlDiff.of(ten, ten.replace("l5\n", "L5\n")).hunks.single()

        hunk.lines.first() shouldBe DiffLine(DiffLineType.CONTEXT, "l2", 2, 2)
        hunk.lines.first { it.type == DiffLineType.REMOVED } shouldBe DiffLine(DiffLineType.REMOVED, "l5", 5, null)
        hunk.lines.first { it.type == DiffLineType.ADDED } shouldBe DiffLine(DiffLineType.ADDED, "L5", null, 5)
    }

    @Test
    fun `context is cut at the start and end of the text`() {
        val diff = YamlDiff.of(lines("a", "b", "c"), lines("A", "b", "c"))

        diff.hunks.single().header shouldBe "@@ -1,3 +1,3 @@"
    }

    @Test
    fun `inserted lines shift the new numbering`() {
        val diff = YamlDiff.of(ten, ten.replace("l3\n", "l3\nnew-a\nnew-b\n"))

        diff.hunks.single().header shouldBe "@@ -1,6 +1,8 @@"
        diff.added shouldBe 2
        diff.removed shouldBe 0
        diff.hunks
            .single()
            .lines
            .filter { it.type == DiffLineType.ADDED }
            .map { it.newLine } shouldBe listOf(4, 5)
    }

    @Test
    fun `deleted lines are shown with their old numbers`() {
        val diff = YamlDiff.of(ten, ten.replace("l9\nl10\n", ""))

        diff.hunks.single().header shouldBe "@@ -6,5 +6,3 @@"
        diff.hunks
            .single()
            .lines
            .filter { it.type == DiffLineType.REMOVED }
            .map { it.oldLine } shouldBe listOf(9, 10)
    }

    @Test
    fun `distant changes form separate hunks and close ones merge`() {
        val twenty = (1..20).joinToString("") { "l$it\n" }

        val distant = YamlDiff.of(twenty, twenty.replace("l2\n", "X\n").replace("l18\n", "Y\n"))
        distant.hunks shouldHaveSize 2
        distant.hunks.map { it.header } shouldBe listOf("@@ -1,5 +1,5 @@", "@@ -15,6 +15,6 @@")

        // Six unchanged lines between the changes: the two contexts of three touch, so it is one hunk.
        val close = YamlDiff.of(twenty, twenty.replace("l5\n", "X\n").replace("l12\n", "Y\n"))
        close.hunks shouldHaveSize 1
        close.hunks.single().header shouldBe "@@ -2,14 +2,14 @@"
    }

    @Test
    fun `zero context shows only the changed lines and GNU numbering for pure insertions`() {
        val diff = YamlDiff.of(lines("a", "b"), lines("a", "x", "b"), context = 0)

        diff.hunks.single().header shouldBe "@@ -1,0 +2 @@"
        diff.hunks.single().lines shouldBe listOf(DiffLine(DiffLineType.ADDED, "x", null, 2))
    }

    @Test
    fun `a new file is all additions from line zero`() {
        val diff = YamlDiff.of("", lines("a", "b", "c"))

        diff.hunks.single().header shouldBe "@@ -0,0 +1,3 @@"
        diff.added shouldBe 3
    }

    @Test
    fun `an emptied file is all deletions`() {
        val diff = YamlDiff.of(lines("a"), "")

        diff.hunks.single().header shouldBe "@@ -1 +0,0 @@"
        diff.unified().lines().drop(2) shouldBe listOf("@@ -1 +0,0 @@", "-a", "")
    }

    @Test
    fun `a missing final line break is a change and is marked like diff -u does`() {
        val diff = YamlDiff.of("a\nb\n", "a\nb")

        diff.unified() shouldBe
            """
            |--- a
            |+++ b
            |@@ -1,2 +1,2 @@
            | a
            |-b
            |+b
            |${YamlDiff.NO_NEWLINE}
            |
            """.trimMargin()
    }

    @Test
    fun `carriage returns are part of the line`() {
        val diff = YamlDiff.of("a\r\nb\r\n", "a\nb\r\n")

        diff.removed shouldBe 1
        diff.hunks
            .single()
            .lines
            .first { it.type == DiffLineType.REMOVED }
            .text shouldBe "a\r"
    }

    @Test
    fun `the edit script is a shortest one`() {
        val old = lines("a", "b", "c", "a", "b", "b", "a")
        val new = lines("c", "b", "a", "b", "a", "c")

        val diff = YamlDiff.of(old, new, context = 0)

        // Myers' classic example: the shortest edit script has five edits.
        (diff.added + diff.removed) shouldBe 5
    }

    @Test
    fun `applying the hunks to the old text gives the new text`() {
        val old = (1..40).joinToString("") { if (it % 7 == 0) "seven $it\n" else "line $it\n" }
        val new =
            old
                .replace("line 3\n", "")
                .replace("seven 14\n", "fourteen\nfourteen and a half\n")
                .replace("line 39\n", "line 39\nline 39.5\n") + "tail\n"

        val diff = YamlDiff.of(old, new, context = 2)

        patch(old, diff) shouldBe new
    }

    @Test
    fun `completely different texts become one replacement`() {
        val old = (1..50).joinToString("") { "old $it\n" }
        val new = (1..30).joinToString("") { "new $it\n" }

        val diff = YamlDiff.of(old, new)

        diff.removed shouldBe 50
        diff.added shouldBe 30
        patch(old, diff) shouldBe new
    }

    @Test
    fun `large texts with many changes stay correct`() {
        val old = (1..3_000).joinToString("") { "line $it\n" }
        val new = (1..3_000).joinToString("") { if (it % 4 == 0) "changed $it\n" else "line $it\n" }

        val diff = YamlDiff.of(old, new)

        patch(old, diff) shouldBe new
        diff.added shouldBe 750
    }

    @Test
    fun `beyond the edit distance limit the middle is replaced as a whole and still patches correctly`() {
        val old = (1..2_500).joinToString("") { "a$it\n" }
        val new = (1..2_500).joinToString("") { "b$it\n" }

        val diff = YamlDiff.of(old, new)

        diff.removed shouldBe 2_500
        diff.added shouldBe 2_500
        patch(old, diff) shouldBe new
    }

    @Test
    fun `negative context is refused`() {
        shouldThrow<IllegalArgumentException> { YamlDiff.of("a", "b", context = -1) }
    }

    /** Rebuilds the new text from the old one and the hunks, the way `patch` would. */
    private fun patch(
        old: String,
        diff: YamlDiff,
    ): String {
        val oldLines = TextLine.split(old)
        val out = StringBuilder()
        var next = 0
        diff.hunks.forEach { hunk ->
            val firstOld = hunk.lines.firstNotNullOfOrNull { it.oldLine } ?: (hunk.oldStart + 1)
            while (next < firstOld - 1) out.append(render(oldLines[next++]))
            hunk.lines.forEach { line ->
                when (line.type) {
                    DiffLineType.CONTEXT -> out.append(render(oldLines[next++]))
                    DiffLineType.REMOVED -> next++
                    DiffLineType.ADDED -> out.append(line.text).append(if (line.missingNewline) "" else "\n")
                }
            }
        }
        while (next < oldLines.size) out.append(render(oldLines[next++]))
        return out.toString()
    }

    private fun render(line: TextLine): String = line.text + if (line.newline) "\n" else ""
}
