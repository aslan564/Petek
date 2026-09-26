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

package az.petek.llm.infrastructure.http

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.SchemaPrompt
import az.petek.llm.infrastructure.StrictSchema
import az.petek.llm.infrastructure.StructuredJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * [LlmClient] for any OpenAI-compatible `chat/completions` endpoint: OpenAI, Ollama, Groq, Mistral, OpenRouter,
 * LM Studio, and the compatibility endpoints of Gemini and Anthropic. One adapter, no vendor SDK.
 *
 * Structured output starts at [OpenAiCompatibleConfig.structured]: a strict `json_schema` (Pətək's schemas rewritten by
 * [StrictSchema]), then `json_object`, then the schema in the prompt. An endpoint that rejects a mode with HTTP 400 is
 * asked again one step down, and the working mode is remembered for later calls.
 *
 * Errors: 429 -> `RateLimited` (with `Retry-After`), 401/403 -> `Unavailable` (the key), 404 -> `Unavailable` (the
 * model or the URL), other 4xx -> `Unavailable`, 5xx and network blips -> `Transient`, a refused connection ->
 * `Unavailable` (the server is not running), too slow -> `Timeout`. Close it to release the connection pool.
 */
class OpenAiCompatibleLlmClient internal constructor(
    private val config: OpenAiCompatibleConfig,
    private val http: HttpClient,
) : LlmClient,
    AutoCloseable {
    constructor(config: OpenAiCompatibleConfig) : this(config, defaultClient(config.timeout))

    override val provider: LlmProviderKey = LlmProviderKey.OPENAI_COMPAT
    override val model: String = config.model

    private val mode = AtomicReference(config.structured)

    override suspend fun complete(request: LlmRequest): LlmResponse {
        require(request.messages.isNotEmpty()) { "LLM request ${request.label} has no messages" }
        while (true) {
            val current = mode.get()
            val (status, body, retryAfter) = post(body(request, current), request.label)
            val next = current.fallback
            if (status == HttpStatusCode.BadRequest.value && next != null && rejectsFormat(body)) {
                logger.info { "The endpoint rejected structured mode ${current.key}; trying ${next.key}" }
                mode.compareAndSet(current, next)
                continue
            }
            if (status !in 200..299) throw failure(status, body, retryAfter, request.label)
            return response(body, current, request.label)
        }
    }

    override fun close() = http.close()

    private fun body(
        request: LlmRequest,
        structured: StructuredMode,
    ): JsonObject =
        buildJsonObject {
            put("model", config.model)
            put("max_tokens", request.maxOutputTokens)
            config.effort?.let { put("reasoning_effort", it) }
            val system =
                if (structured ==
                    StructuredMode.SCHEMA
                ) {
                    request.system
                } else {
                    SchemaPrompt.system(request.system, request.responseSchema)
                }
            putJsonArray("messages") {
                add(message("system", system))
                request.messages.forEach { add(message(roleOf(it), it.content)) }
            }
            when (structured) {
                StructuredMode.SCHEMA -> {
                    putJsonObject("response_format") {
                        put("type", "json_schema")
                        putJsonObject("json_schema") {
                            put("name", SCHEMA_NAME)
                            put("strict", true)
                            put("schema", StrictSchema.of(request.responseSchema))
                        }
                    }
                }

                StructuredMode.JSON_OBJECT -> {
                    putJsonObject("response_format") { put("type", "json_object") }
                }

                StructuredMode.PROMPT -> {
                    // No response_format: the schema travels in the system text.
                }
            }
        }

    private fun message(
        role: String,
        content: String,
    ) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun roleOf(message: LlmMessage): String = if (message.role == LlmRole.ASSISTANT) "assistant" else "user"

    private suspend fun post(
        body: JsonObject,
        label: String,
    ): Triple<Int, String, String?> =
        try {
            val response =
                http.post(config.completionsUrl) {
                    contentType(ContentType.Application.Json)
                    config.apiKey?.let { header(HttpHeaders.Authorization, "Bearer ${it.reveal()}") }
                    setBody(body.toString())
                }
            Triple(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.RetryAfter])
        } catch (e: CancellationException) {
            throw e
        } catch (_: HttpRequestTimeoutException) {
            throw LlmException.Timeout("The OpenAI-compatible endpoint did not answer within ${config.timeout} for $label")
        } catch (e: ConnectException) {
            throw LlmException.Unavailable(
                "Cannot reach the OpenAI-compatible endpoint ${config.baseUrl} for $label (${e.message}); is the server running?",
                e,
            )
        } catch (e: IOException) {
            throw LlmException.Transient("Network error calling ${config.baseUrl} for $label: ${e.message}", e)
        }

    private fun response(
        body: String,
        structured: StructuredMode,
        label: String,
    ): LlmResponse {
        val json =
            StructuredJson.parseOrNull(body) as? JsonObject
                ?: throw LlmException.Transient("The OpenAI-compatible endpoint answered $label with no JSON: ${tail(body)}")
        val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val refusal = (message?.get("refusal") as? JsonPrimitive)?.contentOrNull
        val text = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        if (text == null) {
            throw LlmException.InvalidOutput(
                "The model gave no text for $label" + (refusal?.let { " (refused: $it)" } ?: ""),
                raw = body,
            )
        }
        val answer =
            StructuredJson.parseObject(text)
                ?: throw LlmException.InvalidOutput("The model's answer for $label is not a JSON object", raw = text)
        return LlmResponse(
            output = if (structured == StructuredMode.SCHEMA) StrictSchema.withoutNulls(answer) else answer,
            usage = usage(json["usage"] as? JsonObject),
            model = (json["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: config.model,
            costUsd = null,
        )
    }

    private fun usage(usage: JsonObject?): TokenUsage {
        if (usage == null) return TokenUsage()

        fun tokens(
            json: JsonObject?,
            key: String,
        ) = (json?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
        val cached = tokens(usage["prompt_tokens_details"] as? JsonObject, "cached_tokens")
        return TokenUsage(
            inputTokens = (tokens(usage, "prompt_tokens") - cached).coerceAtLeast(0),
            outputTokens = tokens(usage, "completion_tokens"),
            cacheReadTokens = cached,
        )
    }

    private fun failure(
        status: Int,
        body: String,
        retryAfter: String?,
        label: String,
    ): LlmException {
        val detail = errorMessage(body)
        return when {
            status == HttpStatusCode.TooManyRequests.value -> {
                LlmException.RateLimited("Rate limited (HTTP 429) for $label: $detail", retryAfter?.toLongOrNull()?.seconds)
            }

            status == HttpStatusCode.Unauthorized.value || status == HttpStatusCode.Forbidden.value -> {
                LlmException.Unavailable("The endpoint rejected the key (HTTP $status) for $label: $detail; check PETEK_LLM_API_KEY")
            }

            status == HttpStatusCode.NotFound.value -> {
                LlmException.Unavailable(
                    "Not found (HTTP 404) for $label: $detail; are the model '${config.model}' and ${config.completionsUrl} correct?",
                )
            }

            status >= SERVER_ERROR -> {
                LlmException.Transient("Endpoint error (HTTP $status) for $label: $detail")
            }

            else -> {
                LlmException.Unavailable("The endpoint rejected the request (HTTP $status) for $label: $detail")
            }
        }
    }

    /** `{"error": {"message": ...}}` (OpenAI), `{"error": "..."}` (Ollama), or the body's tail. */
    private fun errorMessage(body: String): String {
        val json = StructuredJson.parseOrNull(body) as? JsonObject
        val error = json?.get("error")
        val message =
            when (error) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        return tail(message ?: body).ifBlank { "no detail" }
    }

    private fun rejectsFormat(body: String): Boolean {
        val text = body.lowercase()
        return FORMAT_MARKERS.any { it in text }
    }

    private fun tail(text: String): String = text.trim().takeLast(MAX_DETAIL_CHARS).trim()

    companion object {
        private const val SCHEMA_NAME = "answer"
        private const val SERVER_ERROR = 500
        private const val MAX_DETAIL_CHARS = 500
        private val FORMAT_MARKERS = listOf("response_format", "json_schema", "json_object", "structured output", "strict")

        internal fun defaultClient(timeout: Duration): HttpClient {
            require(timeout.isPositive()) { "timeout must be positive, was $timeout" }
            return HttpClient(CIO) {
                expectSuccess = false
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = timeout.inWholeMilliseconds
                    connectTimeoutMillis = minOf(timeout, 10.seconds).inWholeMilliseconds
                    socketTimeoutMillis = timeout.inWholeMilliseconds
                }
            }
        }
    }
}
