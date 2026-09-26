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

package az.petek.llm.infrastructure.cli

import az.petek.llm.domain.LlmException
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.StructuredJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the non-Claude agents share when reading their output: JSON lines, token counters, and how a failure's text
 * maps to an [LlmException] (login problems are `Unavailable` with the fix, limits `RateLimited`, the rest
 * `Transient`).
 */
internal object CliOutputs {
    /** Every JSON object printed one per line (JSONL event streams); other lines are ignored. */
    fun jsonLines(stdout: String): List<JsonObject> =
        stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { StructuredJson.parseOrNull(it) as? JsonObject }
            .toList()

    fun string(
        json: JsonObject?,
        key: String,
    ): String? = (json?.get(key) as? JsonPrimitive)?.contentOrNull

    fun long(
        json: JsonObject?,
        key: String,
    ): Long = (json?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L

    fun obj(
        json: JsonElement?,
        key: String,
    ): JsonObject? = (json as? JsonObject)?.get(key) as? JsonObject

    fun usage(
        input: Long,
        output: Long,
        cacheRead: Long = 0,
    ): TokenUsage = TokenUsage(inputTokens = input, outputTokens = output, cacheReadTokens = cacheRead)

    fun failure(
        displayName: String,
        loginHint: String,
        detail: String,
        label: String,
    ): LlmException {
        val text = ClaudeCliResultParser.tail(detail).ifBlank { "no output" }
        val haystack = text.lowercase()
        return when {
            AUTH_MARKERS.any { it in haystack } -> LlmException.Unavailable("$displayName cannot answer: $text. $loginHint")
            RATE_MARKERS.any { it in haystack } -> LlmException.RateLimited("$displayName is rate limited for $label: $text", null)
            USAGE_MARKERS.any { it in haystack } -> LlmException.Unavailable("$displayName rejected Pətək's arguments; update it: $text")
            else -> LlmException.Transient("$displayName failed for $label: $text")
        }
    }

    private val AUTH_MARKERS =
        listOf(
            "not logged in",
            "unauthorized",
            "401",
            "403",
            "authentication",
            "api key",
            "login",
            "credential",
            "billing",
            "quota exceeded for",
        )
    private val RATE_MARKERS = listOf("429", "rate limit", "rate_limit", "usage limit", "too many requests", "resource_exhausted")
    private val USAGE_MARKERS = listOf("unknown option", "unknown argument", "unexpected argument", "unrecognized")
}
