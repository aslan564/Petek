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
