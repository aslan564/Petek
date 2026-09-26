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

import az.petek.llm.LlmTestData
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.SchemaPrompt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The Codex, Gemini and OpenCode profiles over [CliAgentLlmClient], each with a fake process. */
@OptIn(ExperimentalCoroutinesApi::class)
class CliAgentProfilesTest {
    private val request = LlmTestData.request()

    private fun TestScope.client(
        profile: CliAgentProfile,
        runner: ProcessRunner,
    ) = CliAgentLlmClient(profile, 30.seconds, runner, { mapOf("PATH" to "/usr/bin") }, StandardTestDispatcher(testScheduler))

    // --- Codex -----------------------------------------------------------------------------------------------------

    private fun codexRunner(
        lastMessage: String?,
        events: String = CODEX_EVENTS,
        exitCode: Int = 0,
    ) = FakeProcessRunner { spec ->
        val file = Path.of(spec.command[spec.command.indexOf("--output-last-message") + 1])
        lastMessage?.let { Files.writeString(file, it) }
        FakeProcess(stdout = events, exitCode = exitCode)
    }

    @Test
    fun `codex runs exec read-only with the strict schema file, the model, the effort and the prompt on STDIN`() =
        runTest {
            val runner = codexRunner("""{"action":"click","ref":null}""")
            var schemaText = ""
            val recording =
                FakeProcessRunner { spec ->
                    schemaText = Files.readString(Path.of(spec.command[spec.command.indexOf("--output-schema") + 1]))
                    runner.start(spec) as FakeProcess
                }
            val profile = CodexCliProfile(CliAgentConfig(executable = "codex", model = "gpt-5-codex", effort = "low"))

            val response = client(profile, recording).complete(request)

            val command = runner.lastSpec.command
            command.take(6) shouldContainExactly listOf("codex", "exec", "--json", "--skip-git-repo-check", "--sandbox", "read-only")
            command[command.indexOf("--model") + 1] shouldBe "gpt-5-codex"
            command[command.indexOf("-c") + 1] shouldBe "model_reasoning_effort=\"low\""
            command.last() shouldBe "-"
            schemaText shouldContain "\"required\":[\"action\",\"ref\"]"
            runner.lastProcess.stdinText shouldBe "SYSTEM:\nYou are a careful web tester.\n\nClick the publish button"
            response.output shouldBe buildJsonObject { put("action", "click") }
            response.usage shouldBe TokenUsage(inputTokens = 900, outputTokens = 40, cacheReadTokens = 100)
            response.model shouldBe "gpt-5-codex"
        }

    @Test
    fun `codex without a configured model keeps its own and names it default`() =
        runTest {
            val runner = codexRunner("""{"action":"click"}""")
            val client = client(CodexCliProfile(CliAgentConfig(executable = "codex")), runner)

            client.complete(request).model shouldBe "default"
            runner.lastSpec.command.contains("--model") shouldBe false
            client.provider shouldBe LlmProviderKey.CODEX_CLI
        }

    @Test
    fun `codex reads the answer from the event stream when the last message file is missing`() =
        runTest {
            val events =
                """{"type":"item.completed","item":{"type":"agent_message","text":"{\"action\":\"click\"}"}}""" + "\n" + CODEX_EVENTS
            val response = client(CodexCliProfile(CliAgentConfig("codex")), codexRunner(null, events)).complete(request)

            response.output["action"] shouldBe JsonPrimitive("click")
        }

    @Test
    fun `a codex login failure is unavailable with the fix`() =
        runTest {
            val events = """{"type":"error","message":"401 Unauthorized: not logged in"}"""
            val failure =
                shouldThrow<LlmException.Unavailable> {
                    client(CodexCliProfile(CliAgentConfig("codex")), codexRunner(null, events, exitCode = 1)).complete(request)
                }

            failure.message shouldContain "Codex CLI cannot answer"
            failure.message shouldContain CodexCliProfile.LOGIN_HINT
        }

    @Test
    fun `a codex usage limit is rate limited`() =
        runTest {
            val events = """{"type":"turn.failed","error":{"message":"You've hit your usage limit"}}"""
            shouldThrow<LlmException.RateLimited> {
                client(CodexCliProfile(CliAgentConfig("codex")), codexRunner(null, events, exitCode = 1)).complete(request)
            }
        }

    // --- Gemini ----------------------------------------------------------------------------------------------------

    @Test
    fun `gemini gets the schema in the prompt on STDIN and its JSON envelope is read`() =
        runTest {
            val envelope =
                """{"response":"```json\n{\"action\":\"click\"}\n```","stats":{"models":{"gemini-2.5-flash":""" +
                    """{"tokens":{"prompt":500,"candidates":30,"cached":200}}}}}"""
            val runner = FakeProcessRunner.answering(envelope)
            val profile = GeminiCliProfile(CliAgentConfig(executable = "gemini", model = "gemini-2.5-flash"))

            val response = client(profile, runner).complete(request)

            runner.lastSpec.command shouldContainExactly
                listOf("gemini", "-p", GeminiCliProfile.INSTRUCTION, "--output-format", "json", "-m", "gemini-2.5-flash")
            val stdin = runner.lastProcess.stdinText
            stdin shouldStartWith "SYSTEM:\nYou are a careful web tester."
            stdin shouldContain SchemaPrompt.INSTRUCTION
            stdin shouldContain LlmTestData.DECISION_SCHEMA.toString()
            response.output shouldBe buildJsonObject { put("action", "click") }
            response.usage shouldBe TokenUsage(inputTokens = 300, outputTokens = 30, cacheReadTokens = 200)
            response.model shouldBe "gemini-2.5-flash"
        }

    @Test
    fun `a gemini error envelope is classified`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"error":{"type":"ApiError","message":"429 RESOURCE_EXHAUSTED"}}""", exitCode = 1)

            shouldThrow<LlmException.RateLimited> { client(GeminiCliProfile(CliAgentConfig("gemini")), runner).complete(request) }
        }

    @Test
    fun `a gemini answer that is not JSON is invalid output`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"response":"I would click the button."}""")

            shouldThrow<LlmException.InvalidOutput> { client(GeminiCliProfile(CliAgentConfig("gemini")), runner).complete(request) }
        }

    // --- OpenCode --------------------------------------------------------------------------------------------------

    @Test
    fun `opencode gets the prompt as its message argument and its text and step events are read`() =
        runTest {
            val events =
                listOf(
                    """{"type":"step_start","part":{"type":"step-start"}}""",
                    """{"type":"text","part":{"type":"text","text":"{\"action\":"}}""",
                    """{"type":"text","part":{"type":"text","text":"\"click\"}"}}""",
                    """{"type":"step_finish","part":{"type":"step-finish","cost":0.002,""" +
                        """"tokens":{"input":700,"output":25,"cache":{"read":50,"write":0}}}}""",
                ).joinToString("\n")
            val runner = FakeProcessRunner.answering(events)
            val profile = OpenCodeCliProfile(CliAgentConfig(executable = "opencode", model = "vendor/model-x"))

            val response = client(profile, runner).complete(request)

            val command = runner.lastSpec.command
            command.take(6) shouldContainExactly listOf("opencode", "run", "--format", "json", "-m", "vendor/model-x")
            command.last() shouldContain SchemaPrompt.INSTRUCTION
            response.output shouldBe buildJsonObject { put("action", "click") }
            response.usage shouldBe TokenUsage(inputTokens = 700, outputTokens = 25, cacheReadTokens = 50)
            response.costUsd shouldBe 0.002
        }

    @Test
    fun `a conversation reaches every agent as a transcript after the system text`() {
        val messages =
            listOf(
                LlmMessage(LlmRole.USER, "first"),
                LlmMessage(LlmRole.ASSISTANT, "{}"),
                LlmMessage(LlmRole.USER, "second"),
            )

        CliTranscripts.withSystem("sys", messages) shouldBe "SYSTEM:\nsys\n\nUSER:\nfirst\n\nASSISTANT:\n{}\n\nUSER:\nsecond"
    }

    @Test
    fun `the factory serves the known agents and any configured tool, nothing else`() {
        CliAgents.create(LlmProviderKey.CODEX_CLI, CliAgentConfig("codex"))?.provider shouldBe LlmProviderKey.CODEX_CLI
        CliAgents.create(LlmProviderKey.GEMINI_CLI, CliAgentConfig("gemini"))?.provider shouldBe LlmProviderKey.GEMINI_CLI
        CliAgents.create(LlmProviderKey.OPENCODE_CLI, CliAgentConfig("opencode"))?.provider shouldBe LlmProviderKey.OPENCODE_CLI
        CliAgents.create(LlmProviderKey.CLI, CliAgentConfig("any-ai"))?.provider shouldBe LlmProviderKey.CLI
        CliAgents.create(LlmProviderKey.OPENAI_COMPAT, CliAgentConfig("any-ai")) shouldBe null
    }

    private companion object {
        val CODEX_EVENTS =
            listOf(
                """{"type":"thread.started","thread_id":"t1"}""",
                """{"type":"turn.started"}""",
                """{"type":"turn.completed","usage":{"input_tokens":1000,"cached_input_tokens":100,"output_tokens":40}}""",
            ).joinToString("\n")
    }
}
