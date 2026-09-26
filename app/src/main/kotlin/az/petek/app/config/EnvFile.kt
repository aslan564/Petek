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

package az.petek.app.config

import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Reads `.env` files (`KEY=VALUE` per line), the format documented in `.env.example`:
 * - blank lines and lines starting with `#` are ignored; an optional `export ` prefix is accepted;
 * - keys are `[A-Za-z_][A-Za-z0-9_]*`; surrounding whitespace of keys and unquoted values is dropped;
 * - an unquoted value ends at a ` #` comment, so `KEY=   # note` is empty (a `#` directly after `=` or another
 *   character is part of the value, e.g. a token);
 * - `"double"` quotes keep `#` and spaces and understand `\n`, `\r`, `\t`, `\"` and `\\`; `'single'` quotes are literal;
 *   after the closing quote only whitespace or a comment may follow;
 * - a key given twice keeps its last value, like a shell sourcing the file.
 *
 * Every malformed line is reported at once in an [EnvFileException]. Messages name the line and at most the key,
 * never the value, because values are secrets (test token, API key).
 */
object EnvFile {
    /** Values of [path]; an absent file is simply empty (`.env` is optional). */
    fun load(path: Path): Map<String, String> {
        val text =
            try {
                decode(Files.readAllBytes(path))
            } catch (_: NoSuchFileException) {
                return emptyMap()
            } catch (_: CharacterCodingException) {
                throw EnvFileException(path, listOf("the file is not valid UTF-8"))
            } catch (e: IOException) {
                throw EnvFileException(path, listOf("cannot read the file (${e::class.simpleName}: ${e.message})"))
            }
        return parse(text, path)
    }

    /** Parses the text of a `.env` file; [source] only names it in error messages. */
    fun parse(
        text: String,
        source: Path? = null,
    ): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        val problems = mutableListOf<String>()
        text.removePrefix(BYTE_ORDER_MARK).lines().forEachIndexed { index, rawLine ->
            when (val parsed = parseLine(rawLine.removeSuffix("\r"))) {
                is Line.Entry -> values[parsed.key] = parsed.value
                is Line.Problem -> problems += "line ${index + 1}: ${parsed.message}"
                Line.Empty -> Unit
            }
        }
        if (problems.isNotEmpty()) throw EnvFileException(source, problems)
        return values
    }

    private sealed interface Line {
        data object Empty : Line

        data class Entry(
            val key: String,
            val value: String,
        ) : Line

        data class Problem(
            val message: String,
        ) : Line
    }

    private fun parseLine(line: String): Line {
        val content = line.trim()
        if (content.isEmpty() || content.startsWith("#")) return Line.Empty
        val assignment = content.replaceFirst(EXPORT, "")
        val equals = assignment.indexOf('=')
        if (equals < 0) return Line.Problem("expected KEY=VALUE")
        val key = assignment.substring(0, equals).trim()
        if (!KEY.matches(key)) return Line.Problem(invalidName(key))
        // Untrimmed: whether whitespace separates `=` from a `#` decides between a comment and a value.
        return when (val value = parseValue(assignment.substring(equals + 1))) {
            is ParsedValue.Ok -> Line.Entry(key, value.value)
            is ParsedValue.Bad -> Line.Problem("$key: ${value.message}")
        }
    }

    /**
     * Echoes the name only when it looks like a mistyped name (`PETEK-TARGET`, `1BAD`): text with spaces, quotes or
     * colons before the `=` may be the start of a value (`TOKEN: abc=…`), which must not be printed.
     */
    private fun invalidName(key: String): String =
        if (key.isNotEmpty() && key.length <= MAX_ECHOED_NAME && key.all { it.isLetterOrDigit() || it in "_-." }) {
            "'$key' is not a valid variable name"
        } else {
            "the text before '=' is not a valid variable name"
        }

    private sealed interface ParsedValue {
        data class Ok(
            val value: String,
        ) : ParsedValue

        data class Bad(
            val message: String,
        ) : ParsedValue
    }

    /** [raw] is everything after the `=`, leading whitespace included (the line itself is already trimmed). */
    private fun parseValue(raw: String): ParsedValue {
        val value = raw.trimStart()
        return when {
            value.startsWith('"') -> parseDoubleQuoted(value)
            value.startsWith('\'') -> parseSingleQuoted(value)
            else -> ParsedValue.Ok(stripComment(raw))
        }
    }

    private fun parseDoubleQuoted(raw: String): ParsedValue {
        val value = StringBuilder()
        var i = 1
        while (i < raw.length) {
            val char = raw[i]
            when {
                char == '\\' && i + 1 < raw.length -> {
                    value.append(ESCAPES[raw[i + 1]] ?: "\\${raw[i + 1]}")
                    i += 2
                }

                char == '"' -> {
                    return afterClosingQuote(raw.substring(i + 1), value.toString())
                }

                else -> {
                    value.append(char)
                    i++
                }
            }
        }
        return ParsedValue.Bad("the double-quoted value is not closed")
    }

    private fun parseSingleQuoted(raw: String): ParsedValue {
        val end = raw.indexOf('\'', startIndex = 1)
        if (end < 0) return ParsedValue.Bad("the single-quoted value is not closed")
        return afterClosingQuote(raw.substring(end + 1), raw.substring(1, end))
    }

    private fun afterClosingQuote(
        rest: String,
        value: String,
    ): ParsedValue {
        val trailing = rest.trim()
        return if (trailing.isEmpty() || trailing.startsWith("#")) {
            ParsedValue.Ok(value)
        } else {
            ParsedValue.Bad("unexpected text after the closing quote")
        }
    }

    /**
     * `value # comment` -> `value` and `KEY=   # comment` -> empty, like a shell; `abc#def` and `KEY=#abc` keep their
     * `#` (tokens may contain one), because a comment starts only after whitespace.
     */
    private fun stripComment(raw: String): String {
        val comment = INLINE_COMMENT.find(raw) ?: return raw.trim()
        return raw.substring(0, comment.range.first).trim()
    }

    private fun decode(bytes: ByteArray): String =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()

    private val EXPORT = Regex("""^export\s+""")
    private const val MAX_ECHOED_NAME = 64
    private const val BYTE_ORDER_MARK = "﻿"
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val INLINE_COMMENT = Regex("""\s#""")
    private val ESCAPES = mapOf('n' to "\n", 'r' to "\r", 't' to "\t", '"' to "\"", '\\' to "\\")
}
