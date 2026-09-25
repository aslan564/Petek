package az.petek.scenarios.domain

enum class DiffLineType(
    val marker: Char,
) {
    CONTEXT(' '),
    REMOVED('-'),
    ADDED('+'),
}

/**
 * One line of a hunk. [oldLine] / [newLine] are 1-based line numbers in the old / new text (null on the side the
 * line does not exist). [missingNewline] marks the last line of a text that does not end with a line break.
 */
data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int?,
    val newLine: Int?,
    val missingNewline: Boolean = false,
)

/**
 * A group of changes with their surrounding context, in unified-diff numbering: [oldStart] is the first old line of
 * the hunk (1-based), or the line after which lines are inserted when [oldCount] is 0 (GNU diff convention).
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
) {
    /** `@@ -3,7 +3,8 @@`; a count of 1 is omitted, as GNU diff does. */
    val header: String get() = "@@ -${range(oldStart, oldCount)} +${range(newStart, newCount)} @@"

    private fun range(
        start: Int,
        count: Int,
    ): String = if (count == 1) "$start" else "$start,$count"
}

/**
 * Line-based unified diff of two scenario texts (pure; for showing v1 -> v2 to the owner). Lines are compared
 * exactly, including a trailing `\r` and whether the last line ends with a line break. The edit script is a
 * shortest one (Myers' algorithm) after trimming the common prefix and suffix; if the texts differ in more than
 * [MAX_EDIT_DISTANCE] lines, the differing middle is shown as one replacement instead, which keeps memory bounded.
 */
data class YamlDiff(
    val oldLabel: String,
    val newLabel: String,
    val hunks: List<DiffHunk>,
) {
    val identical: Boolean get() = hunks.isEmpty()

    val added: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.type == DiffLineType.ADDED } }

    val removed: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.type == DiffLineType.REMOVED } }

    /** The diff as `diff -u` text (`--- old`, `+++ new`, hunks); empty when the texts are identical. */
    fun unified(): String {
        if (identical) return ""
        return buildString {
            append("--- ").append(oldLabel).append('\n')
            append("+++ ").append(newLabel).append('\n')
            hunks.forEach { hunk ->
                append(hunk.header).append('\n')
                hunk.lines.forEach { line ->
                    append(line.type.marker).append(line.text).append('\n')
                    if (line.missingNewline) append(NO_NEWLINE).append('\n')
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT: Int = 3
        const val MAX_EDIT_DISTANCE: Int = 2_000
        const val NO_NEWLINE: String = "\\ No newline at end of file"

        fun of(
            oldText: String,
            newText: String,
            oldLabel: String = "a",
            newLabel: String = "b",
            context: Int = DEFAULT_CONTEXT,
        ): YamlDiff {
            require(context >= 0) { "context must not be negative, was $context" }
            val old = TextLine.split(oldText)
            val new = TextLine.split(newText)
            return YamlDiff(oldLabel, newLabel, Hunks.build(EditScript.of(old, new), context))
        }
    }
}

/** A line and whether a line break follows it (only the last line of a text may lack one). */
internal data class TextLine(
    val text: String,
    val newline: Boolean,
) {
    companion object {
        fun split(text: String): List<TextLine> {
            if (text.isEmpty()) return emptyList()
            val parts = text.split('\n')
            val endsWithBreak = text.endsWith('\n')
            val count = if (endsWithBreak) parts.size - 1 else parts.size
            return (0 until count).map { i -> TextLine(parts[i], newline = endsWithBreak || i < count - 1) }
        }
    }
}

internal sealed interface Edit {
    data class Keep(
        val old: Int,
        val new: Int,
    ) : Edit

    data class Delete(
        val old: Int,
    ) : Edit

    data class Insert(
        val new: Int,
    ) : Edit
}

/** Indexes of an [Edit] script refer to 0-based positions in the old and new line lists. */
internal class EditScript private constructor(
    val old: List<TextLine>,
    val new: List<TextLine>,
    val edits: List<Edit>,
) {
    companion object {
        fun of(
            old: List<TextLine>,
            new: List<TextLine>,
        ): EditScript {
            var prefix = 0
            while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) {
                suffix++
            }
            val middle = Myers(old.subList(prefix, old.size - suffix), new.subList(prefix, new.size - suffix)).edits()
            val edits =
                buildList {
                    repeat(prefix) { add(Edit.Keep(it, it)) }
                    middle.forEach { edit ->
                        add(
                            when (edit) {
                                is Edit.Keep -> Edit.Keep(edit.old + prefix, edit.new + prefix)
                                is Edit.Delete -> Edit.Delete(edit.old + prefix)
                                is Edit.Insert -> Edit.Insert(edit.new + prefix)
                            },
                        )
                    }
                    val oldTail = old.size - suffix
                    val newTail = new.size - suffix
                    repeat(suffix) { add(Edit.Keep(oldTail + it, newTail + it)) }
                }
            return EditScript(old, new, edits)
        }
    }
}

/**
 * Myers' O((N+M)·D) shortest edit script. Each round's furthest-reaching x per diagonal is kept (O(D²) ints) for the
 * backtrack; beyond [YamlDiff.MAX_EDIT_DISTANCE] rounds the whole range becomes "delete all, insert all".
 */
private class Myers(
    private val a: List<TextLine>,
    private val b: List<TextLine>,
) {
    private val n = a.size
    private val m = b.size
    private val offset = n + m + 1

    fun edits(): List<Edit> {
        if (n == 0 || m == 0) return replaceAll()
        val v = IntArray(2 * offset + 1)
        val rounds = ArrayList<IntArray>()
        val maxD = minOf(n + m, YamlDiff.MAX_EDIT_DISTANCE)
        for (d in 0..maxD) {
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    rounds += snapshot(v, d)
                    return backtrack(rounds)
                }
            }
            rounds += snapshot(v, d)
        }
        return replaceAll()
    }

    /** x per diagonal k of round d, stored at index (k + d) / 2. */
    private fun snapshot(
        v: IntArray,
        d: Int,
    ): IntArray = IntArray(d + 1) { i -> v[offset + 2 * i - d] }

    private fun backtrack(rounds: List<IntArray>): List<Edit> {
        val reversed = ArrayList<Edit>()
        var x = n
        var y = m
        for (d in rounds.size - 1 downTo 1) {
            val previous = rounds[d - 1]

            fun xAt(k: Int): Int = previous[(k + d - 1) / 2]
            val k = x - y
            val prevK = if (k == -d || (k != d && xAt(k - 1) < xAt(k + 1))) k + 1 else k - 1
            val prevX = xAt(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
                reversed += Edit.Keep(x, y)
            }
            if (x == prevX) {
                y--
                reversed += Edit.Insert(y)
            } else {
                x--
                reversed += Edit.Delete(x)
            }
        }
        while (x > 0 && y > 0) {
            x--
            y--
            reversed += Edit.Keep(x, y)
        }
        return reversed.asReversed()
    }

    private fun replaceAll(): List<Edit> = (0 until n).map { Edit.Delete(it) } + (0 until m).map { Edit.Insert(it) }
}

/** Groups an edit script into hunks with [context] lines around each change, merging hunks whose context touches. */
private object Hunks {
    fun build(
        script: EditScript,
        context: Int,
    ): List<DiffHunk> {
        val edits = script.edits
        val changes = edits.indices.filter { edits[it] !is Edit.Keep }
        if (changes.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = (changes.first() - context).coerceAtLeast(0)
        var end = (changes.first() + context).coerceAtMost(edits.size - 1)
        changes.drop(1).forEach { index ->
            if (index - context <= end + 1) {
                end = (index + context).coerceAtMost(edits.size - 1)
            } else {
                ranges += start..end
                start = (index - context).coerceAtLeast(0)
                end = (index + context).coerceAtMost(edits.size - 1)
            }
        }
        ranges += start..end
        return ranges.map { hunk(script, it) }
    }

    private fun hunk(
        script: EditScript,
        range: IntRange,
    ): DiffHunk {
        val edits = script.edits
        // Lines of each text consumed before the hunk starts.
        val oldBefore = edits.subList(0, range.first).count { it !is Edit.Insert }
        val newBefore = edits.subList(0, range.first).count { it !is Edit.Delete }
        val lines =
            range.map { i ->
                when (val edit = edits[i]) {
                    is Edit.Keep -> {
                        val line = script.old[edit.old]
                        DiffLine(DiffLineType.CONTEXT, line.text, edit.old + 1, edit.new + 1, !line.newline)
                    }

                    is Edit.Delete -> {
                        val line = script.old[edit.old]
                        DiffLine(DiffLineType.REMOVED, line.text, edit.old + 1, null, !line.newline)
                    }

                    is Edit.Insert -> {
                        val line = script.new[edit.new]
                        DiffLine(DiffLineType.ADDED, line.text, null, edit.new + 1, !line.newline)
                    }
                }
            }
        val oldCount = lines.count { it.type != DiffLineType.ADDED }
        val newCount = lines.count { it.type != DiffLineType.REMOVED }
        return DiffHunk(
            oldStart = if (oldCount == 0) oldBefore else oldBefore + 1,
            oldCount = oldCount,
            newStart = if (newCount == 0) newBefore else newBefore + 1,
            newCount = newCount,
            lines = lines,
        )
    }
}
