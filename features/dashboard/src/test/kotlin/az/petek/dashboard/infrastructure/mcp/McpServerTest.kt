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

package az.petek.dashboard.infrastructure.mcp

import az.petek.core.testing.FakeHarnessClock
import az.petek.core.testing.SequentialIdGenerator
import az.petek.dashboard.demo.DemoPanelBackend
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.testing.TempDirArtifactStore
import az.petek.orchestration.domain.RunOutcome
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/** The MCP contract over stdio: handshake, tool listing, tool calls, refusals and protocol errors, on the demo backend. */
class McpServerTest {
    @TempDir
    lateinit var dir: Path

    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() = jobs.cancel()

    private suspend fun backend(): PanelBackend {
        val root = dir.resolve("evidence")
        val backend =
            DemoPanelBackend(
                FakeHarnessClock(),
                SequentialIdGenerator(),
                TempDirArtifactStore(root),
                jobs,
                { awaitCancellation() },
                root,
            ) { _, _ ->
                CompletableDeferred<RunOutcome>().await()
            }
        backend.seed()
        return backend
    }

    /** Feeds [requests] (one JSON-RPC message each) to a server and returns the responses it wrote, by request id. */
    private suspend fun exchange(
        vararg requests: String,
        allowWrites: Boolean = false,
        backend: PanelBackend? = null,
    ): Map<String, JsonObject> {
        val served = backend ?: backend()
        val input = ByteArrayInputStream((requests.joinToString("\n") + "\n").toByteArray())
        val output = ByteArrayOutputStream()
        McpServer(
            served,
            McpSettings("https://staging.kadrohr.test", dir.resolve("evidence"), allowWrites, "0.1.0-test"),
            input,
            output,
        ).serve()
        return output
            .toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .associateBy { it["id"].toString() }
    }

    private fun call(
        id: Int,
        tool: String,
        arguments: String = "{}",
    ): String = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

    private fun JsonObject.result(): JsonObject = this["result"].shouldNotBeNull().jsonObject

    private fun JsonObject.text(): String =
        result()["content"]!!
            .jsonArray
            .single()
            .jsonObject["text"]!!
            .jsonPrimitive.content

    @Test
    fun `the handshake answers with a supported protocol version, the server's name and tool capability`() =
        runBlocking<Unit> {
            val answers =
                exchange(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
                    """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                    """{"jsonrpc":"2.0","id":"p","method":"ping"}""",
                    """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""",
                )

            answers.keys shouldBe setOf("1", "\"p\"", "2")
            val init = answers["1"]!!.result()
            init["protocolVersion"]!!.jsonPrimitive.content shouldBe "2025-03-26"
            init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "petek"
            init["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content shouldBe "0.1.0-test"
            init["capabilities"]!!.jsonObject.keys shouldContain "tools"
            init["instructions"]!!.jsonPrimitive.content shouldContain "read-only"
            answers["\"p\""]!!.result() shouldBe JsonObject(emptyMap())
            answers["2"]!!.result()["protocolVersion"]!!.jsonPrimitive.content shouldBe McpServer.LATEST_PROTOCOL
        }

    @Test
    fun `the tool list names every panel use case with a JSON schema, and write tools say so`() =
        runBlocking<Unit> {
            val tools =
                exchange("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["1"]!!
                    .result()["tools"]!!
                    .jsonArray
                    .map { it.jsonObject }
            val names = tools.map { it["name"]!!.jsonPrimitive.content }

            names shouldContainAll
                listOf(
                    "list_targets",
                    "explore_site",
                    "get_exploration",
                    "list_unknowns",
                    "answer_unknown",
                    "generate_scenario",
                    "list_scenarios",
                    "get_scenario",
                    "approve_scenario",
                    "run_campaign",
                    "get_run_status",
                    "get_findings",
                    "get_evidence",
                    "get_triage",
                    "teardown",
                )
            val answer = tools.single { it["name"]!!.jsonPrimitive.content == "answer_unknown" }
            answer["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                listOf("unknownId", "answer")
            answer["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys shouldBe setOf("unknownId", "answer")
            tools.single { it["name"]!!.jsonPrimitive.content == "run_campaign" }["description"]!!.jsonPrimitive.content shouldContain
                "Changes state"
        }

    @Test
    fun `read tools return the panel's JSON as text and structured content`() =
        runBlocking<Unit> {
            val answers =
                exchange(
                    call(1, "list_targets"),
                    call(2, "list_scenarios"),
                    call(3, "list_runs"),
                    call(4, "get_capacity", """{"testers":10}"""),
                )

            val targets = Json.parseToJsonElement(answers["1"]!!.text()).jsonObject
            targets["target"]!!.jsonPrimitive.content shouldBe "https://staging.kadrohr.test"
            targets["allowWrites"]!!.jsonPrimitive.boolean shouldBe false
            answers["1"]!!.result()["isError"]!!.jsonPrimitive.boolean shouldBe false

            val scenarios = Json.parseToJsonElement(answers["2"]!!.text()) as JsonArray
            scenarios.size shouldBe
                answers["2"]!!
                    .result()["structuredContent"]!!
                    .jsonObject["items"]!!
                    .jsonArray.size
            scenarios.first().jsonObject.keys shouldContainAll setOf("id", "name", "version", "status", "runnable")

            (Json.parseToJsonElement(answers["3"]!!.text()) as JsonArray).first().jsonObject.keys shouldContain "runId"
            Json
                .parseToJsonElement(answers["4"]!!.text())
                .jsonObject["requested"]!!
                .jsonPrimitive.int shouldBe 10
        }

    @Test
    fun `a scenario is read by id, an unknown id is a tool error with the panel's message, not a protocol error`() =
        runBlocking<Unit> {
            val backend = backend()
            val id = backend.scenarios().first().id
            val answers =
                exchange(call(1, "get_scenario", """{"id":"$id"}"""), call(2, "get_scenario", """{"id":"scn_nope"}"""), backend = backend)

            Json
                .parseToJsonElement(answers["1"]!!.text())
                .jsonObject["yaml"]!!
                .jsonPrimitive.content shouldContain "campaign"
            answers["2"]!!["error"].shouldBeNull()
            answers["2"]!!
                .result()["isError"]!!
                .jsonPrimitive.boolean
                .shouldBeTrue()
            Json
                .parseToJsonElement(answers["2"]!!.text())
                .jsonObject["error"]!!
                .jsonPrimitive.content shouldBe "Ssenari tapılmadı."
        }

    @Test
    fun `write tools are refused in a read-only session and work when writes are allowed`() =
        runBlocking<Unit> {
            val backend = backend()
            val draft = backend.scenarios().first { it.status.name == "DRAFT" }.id

            val refused = exchange(call(1, "approve_scenario", """{"id":"$draft"}"""), backend = backend)["1"]!!
            refused
                .result()["isError"]!!
                .jsonPrimitive.boolean
                .shouldBeTrue()
            refused.text() shouldContain "--allow-writes"
            backend
                .scenario(draft)!!
                .version.status.name shouldBe "DRAFT"

            val allowed = exchange(call(1, "approve_scenario", """{"id":"$draft"}"""), allowWrites = true, backend = backend)["1"]!!
            allowed.result()["isError"]!!.jsonPrimitive.boolean shouldBe false
            Json
                .parseToJsonElement(allowed.text())
                .jsonObject["status"]!!
                .jsonPrimitive.content shouldBe "APPROVED"
        }

    @Test
    fun `protocol mistakes get JSON-RPC errors with the standard codes`() =
        runBlocking<Unit> {
            val answers =
                exchange(
                    "{not json",
                    """{"jsonrpc":"2.0","id":1,"method":"resources/list"}""",
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"no_such_tool"}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_scenario","arguments":{}}}""",
                    """{"jsonrpc":"1.0","id":4,"method":"ping"}""",
                    """{"jsonrpc":"2.0","method":"notifications/whatever"}""",
                )

            fun code(id: String) = answers[id]!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int
            code("null") shouldBe JsonRpcError.PARSE_ERROR
            code("1") shouldBe JsonRpcError.METHOD_NOT_FOUND
            code("2") shouldBe JsonRpcError.INVALID_PARAMS
            code("3") shouldBe JsonRpcError.INVALID_PARAMS
            answers["3"]!!["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldContain "id is required"
            code("4") shouldBe JsonRpcError.INVALID_REQUEST
            answers.size shouldBe 5
        }
}
