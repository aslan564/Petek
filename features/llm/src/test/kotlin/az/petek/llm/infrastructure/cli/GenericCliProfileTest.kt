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
import az.petek.llm.domain.LlmProviderKey
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
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GenericCliProfileTest {
    private val request = LlmTestData.request()

    private fun TestScope.client(
        config: CliAgentConfig,
        runner: ProcessRunner,
        environment: Map<String, String> = mapOf("PATH" to "/usr/bin"),
    ) = CliAgentLlmClient(GenericCliProfile(config), 30.seconds, runner, { environment }, StandardTestDispatcher(testScheduler))

    private fun config(
        arguments: String,
        model: String? = null,
        unset: List<String> = emptyList(),
    ) = CliAgentConfig(executable = "any-ai", model = model, arguments = CliArguments.split(arguments), unsetEnvironment = unset)

    @Test
    fun `the template becomes the argument vector and the system text leads STDIN when the template does not take it`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"action":"click"}""")

            val response = client(config("-p --model {model}", model = "big-1"), runner).complete(request)

            runner.lastSpec.command shouldContainExactly listOf("any-ai", "-p", "--model", "big-1")
            runner.lastProcess.stdinText shouldStartWith "SYSTEM:\n${request.system}"
            runner.lastProcess.stdinText shouldContain SchemaPrompt.INSTRUCTION
            response.output["action"] shouldBe JsonPrimitive("click")
            response.model shouldBe "big-1"
        }

    @Test
    fun `an unset model drops its placeholder and the option before it`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"action":"click"}""")

            client(config("-p --model {model} --quiet"), runner).complete(request)

            runner.lastSpec.command shouldContainExactly listOf("any-ai", "-p", "--quiet")
        }

    @Test
    fun `a template that takes the system text gets it as an argument and STDIN keeps only the conversation`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"action":"click"}""")

            client(config("""--system-prompt "{system}" --schema {schema}"""), runner).complete(request)

            val command = runner.lastSpec.command
            command[2] shouldStartWith request.system
            command[4] shouldContain "\"required\""
            runner.lastProcess.stdinText shouldBe "Click the publish button"
        }

    @Test
    fun `a schema file placeholder names a file that holds the schema`() =
        runTest {
            var schema = ""
            val runner =
                FakeProcessRunner { spec ->
                    schema = Files.readString(Path.of(spec.command.last()))
                    FakeProcess(stdout = """{"action":"click"}""")
                }

            client(config("--output-schema {schema_file}"), runner).complete(request)

            schema shouldContain "\"action\""
        }

    @Test
    fun `the answer inside a result envelope is found by the schema's required fields, with usage and cost`() =
        runTest {
            val envelope =
                """{"type":"result","is_error":false,"result":"{\"action\":\"click\"}","total_cost_usd":0.02,""" +
                    """"usage":{"input_tokens":120,"output_tokens":8,"cache_read_input_tokens":30}}"""
            val runner = FakeProcessRunner.answering(envelope)

            val response = client(config("-p"), runner).complete(request)

            response.output["action"] shouldBe JsonPrimitive("click")
            response.usage.inputTokens shouldBe 120
            response.usage.outputTokens shouldBe 8
            response.usage.cacheReadTokens shouldBe 30
            response.costUsd shouldBe 0.02
        }

    @Test
    fun `a structured output field is taken as the answer`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"is_error":false,"result":"done","structured_output":{"action":"click"}}""")

            client(config("-p"), runner).complete(request).output["action"] shouldBe JsonPrimitive("click")
        }

    @Test
    fun `an error envelope about the login says how to log in`() =
        runTest {
            val runner =
                FakeProcessRunner.answering(
                    """{"is_error":true,"result":"Failed to authenticate: session expired, please login"}""",
                )

            val error = shouldThrow<LlmException.Unavailable> { client(config("-p"), runner).complete(request) }

            error.message.orEmpty() shouldContain "session expired"
            error.message.orEmpty() shouldContain GenericCliProfile.LOGIN_HINT
        }

    @Test
    fun `refused arguments point at the template and the tool's help`() =
        runTest {
            val runner = FakeProcessRunner.answering(stdout = "", stderr = "error: unknown option '--old-flag'", exitCode = 1)

            val error = shouldThrow<LlmException.Unavailable> { client(config("--old-flag"), runner).complete(request) }

            error.message.orEmpty() shouldContain "check the arguments in PETEK_LLM_ARGS with `any-ai --help`"
        }

    @Test
    fun `text without the required fields is invalid output, not an answer`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"thinking":"hmm"}""")

            shouldThrow<LlmException.InvalidOutput> { client(config("-p"), runner).complete(request) }
        }

    @Test
    fun `configured variables are removed from the child's environment, by name or by prefix`() =
        runTest {
            val runner = FakeProcessRunner.answering("""{"action":"click"}""")
            val inherited = mapOf("PATH" to "/usr/bin", "OUTER_SESSION" to "1", "OUTER_X" to "2", "OUTER" to "3", "KEEP" to "4")

            client(config("-p", unset = listOf("OUTER_*", "KEEP_NOT")), runner, inherited).complete(request)

            runner.lastSpec.environment.keys shouldBe setOf("PATH", "OUTER", "KEEP")
        }

    @Test
    fun `the profile names no vendor`() {
        GenericCliProfile(config("-p")).provider shouldBe LlmProviderKey.CLI
    }
}
