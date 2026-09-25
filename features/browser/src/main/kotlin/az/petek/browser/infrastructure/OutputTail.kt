package az.petek.browser.infrastructure

/**
 * The last [maxLines] lines a child process printed, kept so that a startup failure can say why it failed
 * without buffering unbounded output. Thread-safe: reader threads append while the owner reads.
 */
internal class OutputTail(
    private val maxLines: Int = 40,
) {
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        val cleaned = line.substringAfterLast('\r').trimEnd()
        if (cleaned.isEmpty()) return
        synchronized(lines) {
            lines.addLast(cleaned)
            if (lines.size > maxLines) lines.removeFirst()
        }
    }

    override fun toString(): String =
        synchronized(lines) {
            if (lines.isEmpty()) "(no output)" else lines.joinToString(separator = "\n")
        }
}
