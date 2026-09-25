package az.petek.campaign.infrastructure

import az.petek.campaign.domain.SourceLines

/**
 * `actor: [manager[IT], manager[HR]]` is the list form documented in docs/PLAN.md and used by `scenarios/kadrohr.yaml`,
 * but it is not valid YAML: inside a flow sequence `[` and `]` are indicators, so `manager[IT]` cannot be a plain item.
 * This rewrites such single-line actor lists to quoted items (`actor: ["manager[IT]", "manager[HR]"]`) without moving
 * any line, so the documented form loads and every reported line still matches the file.
 *
 * Only lines whose value is a one-line flow list containing brackets are touched; lists that already quote their items,
 * span several lines or are followed by anything but a comment are left for the YAML parser to judge.
 */
internal fun quoteActorFlowLists(text: String): String = text.split('\n').joinToString("\n", transform = ::quoteActorFlowList)

/** 1-based numbers of the lines [quoteActorFlowLists] changed; the line count never changes, so they align. */
internal fun changedLines(
    original: String,
    repaired: String,
): Set<Int> =
    original
        .split('\n')
        .zip(repaired.split('\n'))
        .mapIndexedNotNullTo(LinkedHashSet()) { index, (before, after) -> (index + 1).takeIf { before != after } }

/**
 * Whether every line in [changed] holds the `actor` key of a setup or main step in the repaired tree. A line that
 * merely looks like `actor: [...]` elsewhere (inside a `do: |` block, say) must never be rewritten silently.
 */
internal fun onlyStepActorsChanged(
    changed: Set<Int>,
    lines: SourceLines,
): Boolean {
    val actorLines =
        lines.byPath
            .filterKeys { STEP_ACTOR_PATH.matches(it) }
            .values
            .toSet()
    return actorLines.containsAll(changed)
}

private val STEP_ACTOR_PATH = Regex("""(setup|steps)\[\d+]\.actor""")

internal fun quoteActorFlowList(line: String): String {
    val prefix = ACTOR_FLOW_LIST_START.find(line)?.groupValues?.get(1) ?: return line
    val open = prefix.length
    val close = matchingBracket(line, open) ?: return line
    val rest = line.substring(close + 1)
    if (rest.isNotBlank() && !rest.first().isWhitespace()) return line
    if (rest.isNotBlank() && !rest.trimStart().startsWith("#")) return line
    val inner = line.substring(open + 1, close)
    if ('[' !in inner) return line
    val items = splitTopLevel(inner).map(String::trim)
    if (items.any(String::isEmpty)) return line
    return prefix + items.joinToString(", ", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } + rest
}

/** Index of the `]` closing the `[` at [open]; null when unbalanced or when quotes show the author already quoted. */
private fun matchingBracket(
    line: String,
    open: Int,
): Int? {
    var depth = 0
    for (index in open until line.length) {
        when (line[index]) {
            '"', '\'' -> return null
            '[' -> depth++
            ']' -> if (--depth == 0) return index
        }
    }
    return null
}

/** Splits on commas outside brackets: `a[x, y], b` -> `a[x, y]`, `b`. */
private fun splitTopLevel(text: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    text.forEachIndexed { index, char ->
        when (char) {
            '[' -> {
                depth++
            }

            ']' -> {
                depth--
            }

            ',' -> {
                if (depth == 0) {
                    parts += text.substring(start, index)
                    start = index + 1
                }
            }
        }
    }
    parts += text.substring(start)
    return parts
}

/** `actor:` as a step key (optionally the first key of a list item) followed by the start of a flow list. */
private val ACTOR_FLOW_LIST_START = Regex("""^(\s*(?:-\s+)?actor\s*:\s*)\[""")
