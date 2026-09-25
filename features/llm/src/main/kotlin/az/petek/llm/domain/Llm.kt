/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.domain

import az.petek.core.error.PetekException
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

/**
 * One decision request. The model must answer with JSON that matches [responseSchema] (structured output);
 * the caller still validates the answer in code (CLAUDE.md rule 3: the whitelist lives in code, not in the prompt).
 */
data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val responseSchema: JsonObject,
    val maxOutputTokens: Int = 2048,
    /** For logs and usage accounting, e.g. `a07/announce`. */
    val label: String,
)

data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
) {
    operator fun plus(other: TokenUsage) =
        TokenUsage(
            inputTokens + other.inputTokens,
            outputTokens + other.outputTokens,
            cacheReadTokens + other.cacheReadTokens,
            cacheCreationTokens + other.cacheCreationTokens,
        )
}

data class LlmResponse(
    val output: JsonObject,
    val usage: TokenUsage,
    val model: String,
    /** Reported by the provider when available (the Claude CLI reports it; the API does not). */
    val costUsd: Double?,
)

/** Which backend answers. New providers are added as new infrastructure classes (open/closed). */
enum class LlmProviderId(
    val key: String,
) {
    /** Claude Code CLI in headless mode (`claude -p`), using the user's Claude plan login. */
    CLAUDE_CLI("claude-cli"),

    /** Anthropic Messages API with an API key (official Java SDK). */
    ANTHROPIC_API("anthropic-api"),
    ;

    companion object {
        fun fromKey(key: String): LlmProviderId? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** Port implemented by every provider. */
interface LlmClient {
    val provider: LlmProviderId
    val model: String

    suspend fun complete(request: LlmRequest): LlmResponse
}

sealed class LlmException(
    message: String,
    cause: Throwable? = null,
) : PetekException(message, cause) {
    /** Not usable at all (not logged in, no credit, CLI missing). Retrying will not help. */
    class Unavailable(
        message: String,
        cause: Throwable? = null,
    ) : LlmException(message, cause)

    class RateLimited(
        message: String,
        val retryAfter: Duration?,
    ) : LlmException(message)

    /** Network blips, 5xx, overloaded. Safe to retry. */
    class Transient(
        message: String,
        cause: Throwable? = null,
    ) : LlmException(message, cause)

    /** The model answered but not with valid JSON for the schema. */
    class InvalidOutput(
        message: String,
        val raw: String,
    ) : LlmException(message)

    class Timeout(
        message: String,
    ) : LlmException(message)
}
