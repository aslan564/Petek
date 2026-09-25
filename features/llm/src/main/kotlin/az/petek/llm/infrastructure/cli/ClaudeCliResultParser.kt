/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.cli

import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.StructuredJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** What a finished `claude` process left behind; stdout and stderr are already decoded (stderr only its tail). */
internal data class ProcessOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Turns the output of `claude -p --output-format json` into an [LlmResponse] or the [LlmException] that tells the
 * caller whether retrying makes sense. Unknown keys are ignored: the CLI adds fields between releases.
 *
 * @param apiKeyInEnvironment adds a hint to billing errors: with `ANTHROPIC_API_KEY` set the CLI bills that key
 *   instead of the user's Claude plan, which is the usual cause of "Credit balance is too low".
 */
internal class ClaudeCliResultParser(
    private val configuredModel: String,
    private val apiKeyInEnvironment: Boolean,
) {
    fun parse(
        output: ProcessOutput,
        label: String,
    ): LlmResponse {
        val envelope = findEnvelope(output.stdout) ?: throw withoutEnvelope(output, label)
        if (envelope.isError) throw classifyError(envelope, output, label)
        if (output.exitCode != 0) {
            throw LlmException.Transient(
                "Claude CLI exited with code ${output.exitCode} for $label: ${tail(output.stderr.ifBlank { output.stdout })}",
            )
        }
        return LlmResponse(
            output = structuredAnswer(envelope, label),
            usage = envelope.usage(),
            model = envelope.firstModel() ?: configuredModel,
            costUsd = envelope.double("total_cost_usd"),
        )
    }

    private fun structuredAnswer(
        envelope: Envelope,
        label: String,
    ): JsonObject {
        val structured = envelope.json["structured_output"]
        val text =
            when (structured) {
                is JsonObject -> return structured
                null, JsonNull -> envelope.string("result").orEmpty()
                is JsonPrimitive -> structured.content
                is JsonArray -> structured.toString()
            }
        return StructuredJson.parseObject(text)
            ?: throw LlmException.InvalidOutput("Claude CLI answer for $label is not a JSON object", raw = text)
    }

    private fun classifyError(
        envelope: Envelope,
        output: ProcessOutput,
        label: String,
    ): LlmException {
        val detail = envelope.errorDetail()
        val status = envelope.int("api_error_status")
        val subtype = envelope.string("subtype")
        val haystack =
            listOfNotNull(detail, envelope.string("terminal_reason"), subtype, output.stderr)
                .joinToString(" ")
                .lowercase()
        return when {
            AUTH_MARKERS.any { it in haystack } || status in AUTH_STATUSES -> {
                unavailable(detail)
            }

            RATE_LIMIT_MARKERS.any { it in haystack } || status == HTTP_TOO_MANY_REQUESTS -> {
                LlmException.RateLimited("Claude CLI is rate limited for $label: $detail", retryAfter = null)
            }

            OVERLOAD_MARKERS.any { it in haystack } || (status != null && status >= HTTP_SERVER_ERROR) -> {
                LlmException.Transient("Claude API error${status.suffix()} for $label: $detail")
            }

            subtype in INVALID_OUTPUT_SUBTYPES -> {
                LlmException.InvalidOutput(
                    "Claude CLI gave no valid structured answer for $label ($subtype): $detail",
                    raw = envelope.string("result") ?: output.stdout,
                )
            }

            status != null && status >= HTTP_CLIENT_ERROR -> {
                LlmException.Unavailable("Claude CLI request was rejected${status.suffix()} for $label: $detail")
            }

            else -> {
                LlmException.Transient("Claude CLI failed for $label: $detail")
            }
        }
    }

    private fun withoutEnvelope(
        output: ProcessOutput,
        label: String,
    ): LlmException {
        val detail = tail(output.stderr.ifBlank { output.stdout }).ifBlank { "no output" }
        val haystack = (output.stdout + " " + output.stderr).lowercase()
        return when {
            AUTH_MARKERS.any { it in haystack } -> {
                unavailable(detail)
            }

            USAGE_ERROR_MARKERS.any { it in haystack } -> {
                LlmException.Unavailable(
                    "Claude CLI rejected Pətək's arguments; update Claude Code (`claude update`): $detail",
                )
            }

            else -> {
                LlmException.Transient(
                    "Claude CLI returned no JSON result for $label (exit code ${output.exitCode}): $detail",
                )
            }
        }
    }

    private fun unavailable(detail: String): LlmException.Unavailable {
        val hint = if (apiKeyInEnvironment) " $API_KEY_HINT" else ""
        return LlmException.Unavailable("Claude CLI cannot answer: $detail. $LOGIN_HINT$hint")
    }

    /** The result object: the whole stdout, the last `result` element of an array, or the last JSON line. */
    private fun findEnvelope(stdout: String): Envelope? {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return null
        StructuredJson.parseOrNull(trimmed)?.let(::envelopeIn)?.let { return it }
        return trimmed
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { StructuredJson.parseOrNull(it)?.let(::envelopeIn) }
            .lastOrNull()
    }

    private fun envelopeIn(element: JsonElement): Envelope? =
        when (element) {
            is JsonObject -> Envelope(element).takeIf { it.isResult }
            is JsonArray -> element.filterIsInstance<JsonObject>().map(::Envelope).lastOrNull { it.isResult }
            else -> null
        }

    private fun Int?.suffix(): String = if (this == null) "" else " (HTTP $this)"

    private class Envelope(
        val json: JsonObject,
    ) {
        val isResult: Boolean
            get() {
                val type = string("type")
                return type == "result" || (type == null && RESULT_KEYS.any { it in json })
            }

        val isError: Boolean
            get() = primitive("is_error")?.booleanOrNull == true || string("subtype")?.startsWith("error") == true

        fun errorDetail(): String {
            val errors = (json["errors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val detail =
                string("result")?.takeIf { it.isNotBlank() }
                    ?: errors.joinToString("; ").takeIf { it.isNotBlank() }
                    ?: string("terminal_reason")
                    ?: string("subtype")
                    ?: "unknown error"
            return tail(detail)
        }

        fun usage(): TokenUsage {
            val usage = json["usage"] as? JsonObject ?: return TokenUsage()

            fun tokens(key: String) = (usage[key] as? JsonPrimitive)?.longOrNull ?: 0L
            return TokenUsage(
                inputTokens = tokens("input_tokens"),
                outputTokens = tokens("output_tokens"),
                cacheReadTokens = tokens("cache_read_input_tokens"),
                cacheCreationTokens = tokens("cache_creation_input_tokens"),
            )
        }

        fun firstModel(): String? = (json["modelUsage"] as? JsonObject)?.keys?.firstOrNull()?.takeIf { it.isNotBlank() }

        fun string(key: String): String? = primitive(key)?.contentOrNull

        fun int(key: String): Int? = primitive(key)?.intOrNull

        fun double(key: String): Double? = primitive(key)?.doubleOrNull

        private fun primitive(key: String): JsonPrimitive? = json[key] as? JsonPrimitive
    }

    companion object {
        const val MAX_DETAIL_CHARS = 500
        const val LOGIN_HINT = "Run `claude`, then /login with your Claude plan account."
        const val API_KEY_HINT =
            "ANTHROPIC_API_KEY is set, so the CLI bills that API key instead of your Claude plan; " +
                "unset it to use the plan login."

        private const val HTTP_CLIENT_ERROR = 400
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private val AUTH_STATUSES = setOf(401, 402, 403)
        private val AUTH_MARKERS =
            listOf(
                "credit balance",
                "not logged in",
                "please run /login",
                "invalid api key",
                "authentication",
                // "OAuth token has expired", "Invalid auth token · Fix external API key"
                "auth token",
                "billing",
            )

        // "You've hit your session limit · resets 5pm" is how current Claude Code reports a plan limit.
        private val RATE_LIMIT_MARKERS =
            listOf("rate limit", "rate_limit", "usage limit", "too many requests", "you've hit your")
        private val OVERLOAD_MARKERS = listOf("overloaded")
        private val USAGE_ERROR_MARKERS = listOf("unknown option", "unknown argument", "error: option")
        private val INVALID_OUTPUT_SUBTYPES = setOf("error_max_structured_output_retries", "error_max_turns")
        private val RESULT_KEYS = listOf("is_error", "structured_output", "result")

        /** Last [MAX_DETAIL_CHARS] characters, trimmed: error messages stay readable and bounded. */
        fun tail(text: String): String = text.trim().takeLast(MAX_DETAIL_CHARS).trim()
    }
}
