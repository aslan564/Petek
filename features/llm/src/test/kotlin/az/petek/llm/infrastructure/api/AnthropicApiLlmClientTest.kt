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

package az.petek.llm.infrastructure.api

import az.petek.core.security.Secret
import az.petek.llm.LlmTestData
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AnthropicApiLlmClientTest {
    private val api = FakeMessagesApi()
    private val clients = mutableListOf<AnthropicApiLlmClient>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        api.close()
    }

    private fun client(
        baseUrl: String = api.baseUrl,
        timeout: kotlin.time.Duration = 10.seconds,
    ) = AnthropicApiLlmClient(AnthropicApiConfig(apiKey = Secret(API_KEY), model = MODEL, baseUrl = baseUrl, timeout = timeout))
        .also { clients += it }

    private fun complete(request: LlmRequest = LlmTestData.request()): LlmResponse = runBlocking { client().complete(request) }

    private fun respond(
        status: Int = 200,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        api.next = FakeMessagesApi.Canned(status, body, headers)
    }

    private fun sentBody(): JsonObject = Json.parseToJsonElement(api.requests.single().body).jsonObject

    // --- request ----------------------------------------------------------------------------------------------

    @Test
    fun `the request carries model, max_tokens, system, messages and the JSON schema as output format`() {
        respond(body = message(text = CLICK_JSON))

        complete()

        val body = sentBody()
        body["model"]!!.jsonPrimitive.content shouldBe MODEL
        body["max_tokens"]!!.jsonPrimitive.int shouldBe 512
        body["system"]!!.jsonPrimitive.content shouldBe "You are a careful web tester."
        body["output_config"] shouldBe
            buildJsonObject {
                put(
                    "format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("schema", LlmTestData.DECISION_SCHEMA)
                    },
                )
            }
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        messages.map { it["role"]!!.jsonPrimitive.content } shouldContainExactly listOf("user")
        messages.single()["content"]!!.jsonPrimitive.content shouldBe "Click the publish button"
    }

    @Test
    fun `no thinking or sampling parameters are sent`() {
        respond(body = message(text = CLICK_JSON))

        complete()

        val keys = sentBody().keys
        listOf("thinking", "temperature", "top_p", "top_k", "stream").forEach { (it in keys) shouldBe false }
    }

    @Test
    fun `a conversation keeps its roles and order`() {
        respond(body = message(text = CLICK_JSON))
        val messages =
            listOf(
                LlmMessage(LlmRole.USER, "snapshot 1"),
                LlmMessage(LlmRole.ASSISTANT, CLICK_JSON),
                LlmMessage(LlmRole.USER, "snapshot 2"),
            )

        complete(LlmTestData.request(messages = messages))

        val sent = sentBody()["messages"]!!.jsonArray.map { it.jsonObject }
        sent.map { it["role"]!!.jsonPrimitive.content } shouldContainExactly listOf("user", "assistant", "user")
        sent.map { it["content"]!!.jsonPrimitive.content } shouldContainExactly listOf("snapshot 1", CLICK_JSON, "snapshot 2")
    }

    @Test
    fun `a blank system prompt is left out`() {
        respond(body = message(text = CLICK_JSON))

        complete(LlmTestData.request(system = " "))

        ("system" in sentBody()) shouldBe false
    }

    @Test
    fun `the key is sent as x-api-key`() {
        respond(body = message(text = CLICK_JSON))

        complete()

        api.requests.single().headers["x-api-key"] shouldBe API_KEY
    }

    // --- response ---------------------------------------------------------------------------------------------

    @Test
    fun `the first text block is parsed as the output and usage is reported`() {
        respond(body = message(text = """{"action":"click","ref":4}""", withThinking = true))

        val response = complete()

        response.output shouldBe
            buildJsonObject {
                put("action", "click")
                put("ref", 4)
            }
        response.usage shouldBe TokenUsage(inputTokens = 820, outputTokens = 31, cacheReadTokens = 400, cacheCreationTokens = 12)
        response.model shouldBe "test-model-small-20260101"
        response.costUsd shouldBe null
    }

    @Test
    fun `missing cache counters count as zero`() {
        respond(body = message(text = CLICK_JSON, usage = """{"input_tokens":5,"output_tokens":2}"""))

        complete().usage shouldBe TokenUsage(inputTokens = 5, outputTokens = 2)
    }

    @Test
    fun `a fenced JSON answer is accepted`() {
        respond(body = message(text = "```json\n$CLICK_JSON\n```"))

        complete().output shouldBe buildJsonObject { put("action", "click") }
    }

    @Test
    fun `an answer that is not JSON is invalid output with the raw text`() {
        respond(body = message(text = "I would click the button."))

        val error = shouldThrow<LlmException.InvalidOutput> { complete() }

        error.raw shouldBe "I would click the button."
    }

    @Test
    fun `a refusal is invalid output`() {
        respond(body = message(text = "", stopReason = "refusal"))

        val error = shouldThrow<LlmException.InvalidOutput> { complete() }

        error.message shouldContain "refused"
    }

    @Test
    fun `an answer cut off at max_tokens is invalid output`() {
        respond(body = message(text = """{"action":"cli""", stopReason = "max_tokens"))

        val error = shouldThrow<LlmException.InvalidOutput> { complete() }

        error.message shouldContain "max_tokens=512"
        error.raw shouldBe """{"action":"cli"""
    }

    @Test
    fun `an answer without any text block is invalid output`() {
        respond(body = message(text = null, withThinking = true))

        shouldThrow<LlmException.InvalidOutput> { complete() }
    }

    // --- errors -----------------------------------------------------------------------------------------------

    @Test
    fun `429 is rate limited with the retry-after the server sent`() {
        respond(
            429,
            error("rate_limit_error", "Number of request tokens has exceeded your per-minute rate limit"),
            mapOf("retry-after" to "7"),
        )

        val error = shouldThrow<LlmException.RateLimited> { complete() }

        error.retryAfter shouldBe 7.seconds
        error.message shouldContain "per-minute rate limit"
    }

    @Test
    fun `retry-after-ms is preferred for sub-second precision`() {
        respond(429, error("rate_limit_error", "slow down"), mapOf("retry-after-ms" to "1500", "retry-after" to "2"))

        shouldThrow<LlmException.RateLimited> { complete() }.retryAfter shouldBe 1_500.milliseconds
    }

    @Test
    fun `429 without retry-after is rate limited without a hint`() {
        respond(429, error("rate_limit_error", "slow down"))

        shouldThrow<LlmException.RateLimited> { complete() }.retryAfter shouldBe null
    }

    @Test
    fun `401 is unavailable and never reveals the key`() {
        respond(401, error("authentication_error", "invalid x-api-key"))

        val error = shouldThrow<LlmException.Unavailable> { complete() }

        error.message shouldContain "invalid x-api-key"
        error.message shouldContain "ANTHROPIC_API_KEY"
        error.message shouldNotContain API_KEY
        error.stackTraceToString() shouldNotContain API_KEY
    }

    @Test
    fun `403 is unavailable`() {
        respond(403, error("permission_error", "Your API key does not have permission to use the specified resource."))

        shouldThrow<LlmException.Unavailable> { complete() }
    }

    @Test
    fun `400 is unavailable with the API's explanation`() {
        respond(400, error("invalid_request_error", "output_config.format.schema: unsupported keyword"))

        val error = shouldThrow<LlmException.Unavailable> { complete() }

        error.message shouldContain "output_config.format.schema: unsupported keyword"
        error.message shouldContain "HTTP 400"
    }

    @Test
    fun `404 is unavailable and names the configured model`() {
        respond(404, error("not_found_error", "model: $MODEL"))

        shouldThrow<LlmException.Unavailable> { complete() }.message shouldContain "is the model id '$MODEL' correct?"
    }

    @Test
    fun `402 billing problems are unavailable`() {
        respond(402, error("billing_error", "Your credit balance is too low."))

        shouldThrow<LlmException.Unavailable> { complete() }.message shouldContain "credit balance"
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503, 529])
    fun `server errors and overload are transient`(status: Int) {
        respond(status, error("overloaded_error", "Overloaded"))

        val error = shouldThrow<LlmException.Transient> { complete() }

        error.message shouldContain "HTTP $status"
    }

    @Test
    fun `a request timeout from the server is transient`() {
        respond(408, error("timeout_error", "Request timed out"))

        shouldThrow<LlmException.Transient> { complete() }
    }

    @Test
    fun `a server that cannot be reached is transient`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        shouldThrow<LlmException.Transient> {
            runBlocking {
                client(
                    baseUrl = "http://127.0.0.1:$closedPort",
                ).complete(LlmTestData.request())
            }
        }
    }

    @Test
    fun `a server slower than the timeout is a timeout`() {
        api.next = FakeMessagesApi.Canned(body = message(text = CLICK_JSON), delay = 5.seconds)

        val error =
            shouldThrow<LlmException.Timeout> {
                runBlocking { client(timeout = 300.milliseconds).complete(LlmTestData.request()) }
            }

        error.message shouldContain "a07/announce"
    }

    // --- configuration ----------------------------------------------------------------------------------------

    @Test
    fun `provider and model come from the configuration`() {
        val client = client()

        client.provider shouldBe LlmProviderKey.ANTHROPIC_API
        client.model shouldBe MODEL
    }

    @Test
    fun `the configuration never prints the key and rejects unusable values`() {
        val config = AnthropicApiConfig(apiKey = Secret(API_KEY), model = MODEL)

        config.toString() shouldNotContain API_KEY
        config.maxRetries shouldBe 0
        shouldThrow<IllegalArgumentException> { AnthropicApiConfig(apiKey = Secret(" "), model = MODEL) }
        shouldThrow<IllegalArgumentException> { AnthropicApiConfig(apiKey = Secret(API_KEY), model = "") }
        shouldThrow<IllegalArgumentException> { AnthropicApiConfig(apiKey = Secret(API_KEY), model = MODEL, timeout = 0.seconds) }
        shouldThrow<IllegalArgumentException> { AnthropicApiConfig(apiKey = Secret(API_KEY), model = MODEL, maxRetries = -1) }
        shouldThrow<IllegalArgumentException> { AnthropicApiConfig(apiKey = Secret(API_KEY), model = MODEL, baseUrl = "") }
    }

    private companion object {
        const val API_KEY = "sk-ant-test-0123456789"
        const val MODEL = "test-model-small"
        const val CLICK_JSON = """{"action":"click"}"""

        fun message(
            text: String?,
            stopReason: String = "end_turn",
            withThinking: Boolean = false,
            usage: String = """{"input_tokens":820,"output_tokens":31,"cache_read_input_tokens":400,"cache_creation_input_tokens":12}""",
        ): String {
            val blocks =
                listOfNotNull(
                    if (withThinking) """{"type":"thinking","thinking":"","signature":"c2ln"}""" else null,
                    text?.let {
                        buildJsonObject {
                            put("type", "text")
                            put("text", it)
                        }.toString()
                    },
                )
            return """{"id":"msg_01","type":"message","role":"assistant","model":"test-model-small-20260101",""" +
                """"content":[${blocks.joinToString(",")}],"stop_reason":"$stopReason","stop_sequence":null,""" +
                """"usage":$usage}"""
        }

        fun error(
            type: String,
            message: String,
        ) = buildJsonObject {
            put("type", "error")
            put(
                "error",
                buildJsonObject {
                    put("type", type)
                    put("message", message)
                },
            )
        }.toString()
    }
}
