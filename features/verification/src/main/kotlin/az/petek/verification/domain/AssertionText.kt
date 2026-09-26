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

package az.petek.verification.domain

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.OracleCondition
import kotlin.time.Duration

/**
 * Human-readable `expected` strings and bounded text for assertion results. Records and reports show these
 * strings side by side (A/B/C), so they must be stable and short; full bodies go to artifacts instead.
 */
internal object AssertionText {
    /** Longest `observed` value kept in a record; the complete text lives in the evidence artifact. */
    const val MAX_OBSERVED_CHARS = 500

    /** Largest HTTP body kept in `http_status` evidence (docs: "<status> <body ≤ 2 KB>"). */
    const val MAX_HTTP_EVIDENCE_BYTES = 2048

    private const val MAX_ERROR_CHARS = 300

    fun describe(spec: AssertionSpec): String =
        when (spec) {
            is AssertionSpec.VisibleText -> "${quote(spec.text)} visible within ${ms(spec.within)}"
            is AssertionSpec.NotVisible -> describeNotVisible(spec)
            is AssertionSpec.Oracle -> describeOracle(spec)
            is AssertionSpec.HttpStatus -> "${spec.method} ${spec.path} -> ${spec.equals}"
            is AssertionSpec.Count -> "count of `${spec.selector}` = ${spec.equals}"
            is AssertionSpec.LatencyMax -> "latency <= ${ms(spec.max)}"
            is AssertionSpec.OnlyOneSucceeds -> describeRace(spec)
        }

    fun ms(duration: Duration): String = "${duration.inWholeMilliseconds} ms"

    fun quote(text: String): String = "\"$text\""

    /** Keeps at most [max] characters, marking the cut so a reader never mistakes it for the whole value. */
    fun clip(
        text: String,
        max: Int = MAX_OBSERVED_CHARS,
    ): String = if (text.length <= max) text else text.take(max) + "… (${text.length - max} more chars)"

    /** Prefix of [text] whose UTF-8 encoding is at most [maxBytes], never splitting a character. */
    fun utf8Prefix(
        text: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = utf8Size(codePoint)
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    /** Short, single-line description of an unexpected failure for a record's note. */
    fun error(error: Throwable): String {
        val message =
            error.message
                ?.lineSequence()
                ?.firstOrNull()
                ?.let { clip(it, MAX_ERROR_CHARS) }
        val name = error::class.simpleName ?: "error"
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    private fun describeNotVisible(spec: AssertionSpec.NotVisible): String {
        val parts =
            listOfNotNull(
                spec.text?.let { "text ${quote(it)}" },
                spec.selector?.let { "selector `$it`" },
            )
        return if (parts.isEmpty()) "nothing not visible (no text or selector given)" else parts.joinToString(" and ") + " not visible"
    }

    /** `exactly one actor succeeds`, plus the deciding requests and the oracle condition when the spec names them. */
    private fun describeRace(spec: AssertionSpec.OnlyOneSucceeds): String =
        buildString {
            append("exactly one actor succeeds")
            spec.request?.let { append(" by `").append(it.describe()).append('`') }
            spec.oracle?.let { append(" and ").append(describeOracle(asOracle(it))) }
        }

    /** The oracle condition of a race as the `oracle` assertion it behaves like. */
    fun asOracle(condition: OracleCondition): AssertionSpec.Oracle =
        AssertionSpec.Oracle(condition.path, condition.field, condition.equals, contains = null)

    private fun describeOracle(spec: AssertionSpec.Oracle): String =
        buildString {
            append("GET ").append(spec.path)
            spec.field?.let { append(" field `").append(it).append('`') }
            val checks =
                listOfNotNull(
                    spec.equals?.let { "= ${quote(it)}" },
                    spec.contains?.let { "contains ${quote(it)}" },
                )
            when {
                checks.isNotEmpty() -> append(' ').append(checks.joinToString(" and "))
                spec.field != null -> append(" exists")
                else -> append(" answers 2xx")
            }
        }

    private fun utf8Size(codePoint: Int): Int =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
}
