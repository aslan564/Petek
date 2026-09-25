package az.petek.app.cli

/**
 * Plain-text tables for command output: columns as wide as their longest cell, two spaces apart, a dashed rule under
 * the header. Unlike a terminal-width layout, nothing is ever cut or wrapped, so an e-mail address stays copyable and
 * the output reads the same in a terminal, a CI log or a file.
 */
internal object TextTable {
    /**
     * @param decorate styles a cell after padding (e.g. colours a status symbol); widths are measured on the raw text.
     */
    fun render(
        header: List<String>,
        rows: List<List<String>>,
        decorate: (column: Int, padded: String) -> String = { _, padded -> padded },
    ): String {
        val cleanRows = rows.map { row -> List(header.size) { clean(row.getOrElse(it) { "" }) } }
        val widths = header.indices.map { column -> (cleanRows.map { it[column] } + header[column]).maxOf { it.length } }
        val lines =
            listOf(header.map(::clean), widths.map { "-".repeat(it) }) +
                cleanRows.map { row -> row.mapIndexed { column, cell -> decorate(column, cell.padEnd(widths[column])) } }
        return lines.joinToString("\n") { cells ->
            cells
                .mapIndexed { column, cell -> if (column < widths.lastIndex) cell.padEnd(widths[column]) else cell }
                .joinToString(SEPARATOR)
                .trimEnd()
        }
    }

    private fun clean(text: String): String = text.replace(LINE_BREAKS, " ")

    private const val SEPARATOR = "  "
    private val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")
}
