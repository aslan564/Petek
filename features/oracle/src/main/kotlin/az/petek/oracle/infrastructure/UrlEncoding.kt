/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
