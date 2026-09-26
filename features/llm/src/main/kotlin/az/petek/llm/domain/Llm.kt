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

/**
 * Which backend answers, as the open key `PETEK_LLM_PROVIDER` names it (e.g. `claude-cli`, `openai-compat`). Open on
 * purpose: a new provider is a new infrastructure class registered under a new key, never an edit of an exhaustive
 * `when` (open/closed). The well-known keys are the constants below.
 */
@JvmInline
value class LlmProviderKey private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        /** Claude Code CLI in headless mode (`claude -p`), using the user's Claude plan login. */
        val CLAUDE_CLI = LlmProviderKey("claude-cli")

        /** Anthropic Messages API with an API key (official Java SDK). */
        val ANTHROPIC_API = LlmProviderKey("anthropic-api")

        /** OpenAI Codex CLI (`codex exec`), using the user's own Codex login. */
        val CODEX_CLI = LlmProviderKey("codex-cli")

        /** Google Gemini CLI (`gemini -p`), using the user's own Gemini login. */
        val GEMINI_CLI = LlmProviderKey("gemini-cli")

        /** OpenCode CLI (`opencode run`), with whatever provider it is configured for. */
        val OPENCODE_CLI = LlmProviderKey("opencode-cli")

        /** Any OpenAI-compatible `chat/completions` endpoint: OpenAI, Ollama, Groq, Mistral, OpenRouter, LM Studio... */
        val OPENAI_COMPAT = LlmProviderKey("openai-compat")

        /** Keys spell as lower-case words joined by `-`, so a typo is refused instead of silently becoming a provider. */
        fun of(key: String): LlmProviderKey? {
            val normalized = key.trim().lowercase()
            return if (KEY.matches(normalized)) LlmProviderKey(normalized) else null
        }

        private val KEY = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    }
}

/** Port implemented by every provider. */
interface LlmClient {
    val provider: LlmProviderKey
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
