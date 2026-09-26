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

package az.petek.oracle.infrastructure

import java.nio.charset.StandardCharsets

/** Percent-encoding for URLs the oracle builds itself (values) and for paths callers rendered (repair only). */
internal object UrlEncoding {
    private const val HEX = "0123456789ABCDEF"
    private val illegalInUri = setOf('"', '<', '>', '\\', '^', '`', '{', '|', '}')
    private val encodedDot = Regex("%2[eE]")
    private val dotSegments = setOf(".", "..")

    /** Encodes everything but RFC 3986 unreserved characters: safe as a path segment or a query value (`+` → `%2B`). */
    fun component(value: String): String =
        buildString {
            value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                val char = (byte.toInt() and 0xFF).toChar()
                if (char.isUnreserved()) append(char) else appendEscaped(byte)
            }
        }

    /**
     * Makes an already rendered path/query addressable without changing its meaning: non-ASCII, spaces and characters
     * illegal in URIs are encoded, a `%` that does not start a valid escape becomes `%25`, everything else is kept.
     * Templates insert raw values, so a `+` in the query is a literal plus (`?by=eli+qa@…`, `?phone=+994…`) and is sent
     * as `%2B`; servers would otherwise read it as a space.
     */
    fun repair(pathAndQuery: String): String =
        buildString {
            var inQuery = false
            var i = 0
            while (i < pathAndQuery.length) {
                val codePoint = pathAndQuery.codePointAt(i)
                when {
                    codePoint == '%'.code && !isEscapeAt(pathAndQuery, i) -> append("%25")
                    codePoint == '+'.code && inQuery -> append("%2B")
                    codePoint in 0x21..0x7E && codePoint.toChar() !in illegalInUri -> appendCodePoint(codePoint)
                    else -> Character.toString(codePoint).toByteArray(StandardCharsets.UTF_8).forEach { appendEscaped(it) }
                }
                if (codePoint == '?'.code) inQuery = true
                if (codePoint == '#'.code) inQuery = false
                i += Character.charCount(codePoint)
            }
        }

    /** True when the path part (before `?`/`#`) has a `.` or `..` segment, also percent-encoded (`%2e%2E`). */
    fun hasDotSegment(pathAndQuery: String): Boolean =
        pathAndQuery
            .substringBefore('?')
            .substringBefore('#')
            .split('/', '\\')
            .any { segment -> segment.replace(encodedDot, ".") in dotSegments }

    private fun Char.isUnreserved(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

    private fun StringBuilder.appendEscaped(byte: Byte) {
        val value = byte.toInt() and 0xFF
        append('%').append(HEX[value shr 4]).append(HEX[value and 0xF])
    }

    private fun isEscapeAt(
        text: String,
        index: Int,
    ): Boolean = index + 2 < text.length && text[index + 1].isHexDigit() && text[index + 2].isHexDigit()

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
