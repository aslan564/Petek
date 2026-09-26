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

import az.petek.core.security.Secret
import az.petek.llm.LlmTestData
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.SchemaPrompt
import az.petek.llm.infrastructure.StructuredJson
import az.petek.llm.infrastructure.api.FakeMessagesApi.Canned
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.time.Duration.Companion.seconds

class OpenAiCompatibleLlmClientTest {
    private val server = FakeChatCompletions()
    private val clients = mutableListOf<OpenAiCompatibleLlmClient>()

    @AfterEach
    fun close() {
        clients.forEach { it.close() }
        server.close()
    }

    private fun client(
        base: String = "${server.baseUrl}/v1",
        apiKey: Secret? = Secret("sk-test-0123456789abcdef"),
        structured: StructuredMode = StructuredMode.SCHEMA,
        effort: String? = null,
    ) = OpenAiCompatibleLlmClient(
        OpenAiCompatibleConfig(URI(base), "llama3.1", apiKey, structured, timeout = 10.seconds, effort = effort),
    ).also { clients += it }

    private fun sent(index: Int = server.requests.lastIndex): JsonObject =
        StructuredJson.parseOrNull(server.requests[index].body) as JsonObject

    @Test
    fun `a strict json schema request goes to chat completions with the key and the answer and usage are read`() =
        runBlocking<Unit> {
            server.answer(Canned(body = completion("""{\"action\":\"click\",\"ref\":null}""")))

            val response = client(effort = "low").complete(LlmTestData.request())

            server.requests.single().path shouldBe "/v1/chat/completions"
            server.requests.single().headers["authorization"] shouldBe "Bearer sk-test-0123456789abcdef"
            val body = sent()
            body["model"] shouldBe JsonPrimitive("llama3.1")
            body["reasoning_effort"] shouldBe JsonPrimitive("low")
            val format = body["response_format"]!!.jsonObject
            format["type"] shouldBe JsonPrimitive("json_schema")
            val schema = format["json_schema"]!!.jsonObject
            schema["strict"] shouldBe JsonPrimitive(true)
            schema["schema"]!!.jsonObject["required"] shouldBe JsonArray(listOf(JsonPrimitive("action"), JsonPrimitive("ref")))
            val messages = body["messages"] as JsonArray
            messages[0].jsonObject["role"] shouldBe JsonPrimitive("system")
            messages[0].jsonObject["content"] shouldBe JsonPrimitive("You are a careful web tester.")
            messages[1].jsonObject["role"] shouldBe JsonPrimitive("user")
            response.output shouldBe buildJsonObject { put("action", "click") }
            response.usage shouldBe TokenUsage(inputTokens = 80, outputTokens = 12, cacheReadTokens = 20)
            response.model shouldBe "llama3.1:8b"
        }

    @Test
    fun `a base without a version gets v1 and a local server needs no key`() =
        runBlocking<Unit> {
            server.answer(Canned(body = completion("""{\"action\":\"click\"}""")))

            client(base = server.baseUrl, apiKey = null).complete(LlmTestData.request())

            server.requests.single().path shouldBe "/v1/chat/completions"
            server.requests
                .single()
                .headers["authorization"]
                .shouldBeNull()
        }

    @Test
    fun `an endpoint that rejects json schema falls back to json object, then to the prompt, and remembers it`() =
        runBlocking<Unit> {
            val rejected = Canned(status = 400, body = """{"error":{"message":"response_format json_schema is not supported"}}""")
            val rejectedObject = Canned(status = 400, body = """{"error":{"message":"response_format json_object unsupported"}}""")
            server.answer(rejected, rejectedObject, Canned(body = completion("""{\"action\":\"click\"}""")))
            val client = client()

            client.complete(LlmTestData.request()).output["action"] shouldBe JsonPrimitive("click")
            client.complete(LlmTestData.request())

            server.requests.size shouldBe 4
            sent(1)["response_format"]!!.jsonObject["type"] shouldBe JsonPrimitive("json_object")
            sent(2)["response_format"].shouldBeNull()
            sent(3)["response_format"].shouldBeNull()
            val system = (sent(3)["messages"] as JsonArray)[0].jsonObject["content"] as JsonPrimitive
            system.content shouldContain SchemaPrompt.INSTRUCTION
        }

    @Test
    fun `status codes map to the exceptions the decorators understand`() =
        runBlocking<Unit> {
            server.answer(Canned(status = 429, body = """{"error":{"message":"slow down"}}""", headers = mapOf("Retry-After" to "7")))
            shouldThrow<LlmException.RateLimited> { client().complete(LlmTestData.request()) }.retryAfter shouldBe 7.seconds

            server.answer(Canned(status = 401, body = """{"error":{"message":"bad key"}}"""))
            shouldThrow<LlmException.Unavailable> { client().complete(LlmTestData.request()) }.message shouldContain "PETEK_LLM_API_KEY"

            server.answer(Canned(status = 404, body = """{"error":"model 'llama3.1' not found"}"""))
            shouldThrow<LlmException.Unavailable> { client().complete(LlmTestData.request()) }.message shouldContain "not found"

            server.answer(Canned(status = 503, body = "overloaded"))
            shouldThrow<LlmException.Transient> { client().complete(LlmTestData.request()) }
        }

    @Test
    fun `a text answer that is not JSON is invalid output and a server that is not running is unavailable`() =
        runBlocking<Unit> {
            server.answer(Canned(body = completion("I would click it.")))
            shouldThrow<LlmException.InvalidOutput> { client().complete(LlmTestData.request()) }

            val closed = client(base = "http://127.0.0.1:9/v1")
            shouldThrow<LlmException.Unavailable> { closed.complete(LlmTestData.request()) }.message shouldContain "is the server running?"
        }

    @Test
    fun `the key never appears in the config's text and the provider is openai-compat`() {
        val client = client()

        client.provider shouldBe LlmProviderKey.OPENAI_COMPAT
        OpenAiCompatibleConfig(URI("https://api.openai.com/v1"), "gpt-5-mini", Secret("sk-secret-value-123456")).toString() shouldBe
            "OpenAiCompatibleConfig(baseUrl=https://api.openai.com/v1, model=gpt-5-mini, apiKey=set, structured=schema, " +
            "timeout=2m, effort=null)"
        OpenAiCompatibleConfig(URI("https://generativelanguage.googleapis.com/v1beta/openai"), "gemini-2.5-flash")
            .completionsUrl shouldBe "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    }

    private fun completion(content: String) =
        """{"model":"llama3.1:8b","choices":[{"index":0,"message":{"role":"assistant","content":"$content"}}],""" +
            """"usage":{"prompt_tokens":100,"completion_tokens":12,"prompt_tokens_details":{"cached_tokens":20}}}"""
}
