package az.petek.llm.infrastructure.cli

import az.petek.llm.LlmTestData
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderId
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeCliLlmClientTest {
    private val config = ClaudeCliConfig(model = "claude-haiku-4-5")
    private val request = LlmTestData.request()
    private val plainEnvironment = mapOf("PATH" to "/usr/bin", "HOME" to "/home/tester")

    private fun TestScope.client(
        runner: ProcessRunner,
        config: ClaudeCliConfig = this@ClaudeCliLlmClientTest.config,
        environment: Map<String, String> = plainEnvironment,
    ) = ClaudeCliLlmClient(config, runner, { environment }, StandardTestDispatcher(testScheduler))

    private suspend fun ClaudeCliLlmClient.answer(): LlmResponse = complete(request)

    // --- invocation -------------------------------------------------------------------------------------------

    @Test
    fun `the CLI is started with the exact isolated argument list`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)

            client(runner).answer()

            runner.lastSpec.command shouldContainExactly
                listOf(
                    "claude",
                    "-p",
                    "--output-format",
                    "json",
                    "--json-schema",
                    LlmTestData.DECISION_SCHEMA.toString(),
                    "--model",
                    "claude-haiku-4-5",
                    "--system-prompt",
                    "You are a careful web tester.",
                    "--tools",
                    "",
                    "--strict-mcp-config",
                    "--setting-sources",
                    "",
                    "--no-session-persistence",
                    "--permission-prompts",
                    "none",
                    "--effort",
                    "low",
                )
        }

    @Test
    fun `a custom executable is used and effort is left out when not configured`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)
            val custom = ClaudeCliConfig(executable = "/opt/claude/bin/claude", model = "sonnet", effort = null)

            client(runner, config = custom).answer()

            val command = runner.lastSpec.command
            command.first() shouldBe "/opt/claude/bin/claude"
            command.contains("--effort") shouldBe false
            command.last() shouldBe "none"
            command[command.indexOf("--model") + 1] shouldBe "sonnet"
        }

    @Test
    fun `the schema is passed as compact JSON`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)

            client(runner).answer()

            val schemaArgument = runner.lastSpec.command[runner.lastSpec.command.indexOf("--json-schema") + 1]
            schemaArgument shouldBe
                """{"type":"object","properties":{"action":{"type":"string","enum":["click"]},""" +
                """"ref":{"type":"integer"}},"required":["action"],"additionalProperties":false}"""
        }

    @Test
    fun `nested session markers are removed from the inherited environment`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)
            val inherited =
                plainEnvironment +
                    mapOf(
                        "CLAUDECODE" to "1",
                        "CLAUDE_CODE_ENTRYPOINT" to "cli",
                        "CLAUDE_CODE_SSE_PORT" to "40123",
                        "CLAUDE_CODE_SESSION_ID" to "parent-session",
                        "CLAUDE_CODE_OAUTH_TOKEN" to "headless-login",
                        "CLAUDE_CONFIG_DIR" to "/home/tester/.claude",
                        "LANG" to "az_AZ.UTF-8",
                    )

            client(runner, environment = inherited).answer()

            runner.lastSpec.environment shouldBe
                plainEnvironment +
                mapOf(
                    "CLAUDE_CODE_OAUTH_TOKEN" to "headless-login",
                    "CLAUDE_CONFIG_DIR" to "/home/tester/.claude",
                    "LANG" to "az_AZ.UTF-8",
                )
        }

    @Test
    fun `a single user message is sent through stdin as is`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)

            client(runner).complete(LlmTestData.request(messages = listOf(LlmMessage(LlmRole.USER, "Elan yarat: ə ş ğ"))))

            runner.lastProcess.stdinText shouldBe "Elan yarat: ə ş ğ"
        }

    @Test
    fun `a conversation is sent through stdin as a transcript`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)
            val messages =
                listOf(
                    LlmMessage(LlmRole.USER, "Page snapshot 1"),
                    LlmMessage(LlmRole.ASSISTANT, """{"action":"click","ref":3}"""),
                    LlmMessage(LlmRole.USER, "Page snapshot 2"),
                )

            client(runner).complete(LlmTestData.request(messages = messages))

            runner.lastProcess.stdinText shouldBe
                "USER:\nPage snapshot 1\n\nASSISTANT:\n{\"action\":\"click\",\"ref\":3}\n\nUSER:\nPage snapshot 2"
        }

    @Test
    fun `each call runs in its own fresh working directory that is removed afterwards`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)
            val client = client(runner)

            client.answer()
            client.answer()

            runner.workingDirectoryWasEmpty shouldContainExactly listOf(true, true)
            runner.specs[0].workingDirectory shouldNotBe runner.specs[1].workingDirectory
            runner.specs.forEach { spec ->
                listOf(spec.stdinFile, spec.stdoutFile, spec.stderrFile).forEach { file ->
                    file.startsWith(spec.workingDirectory) shouldBe false
                    file.parent shouldBe spec.workingDirectory.parent
                    Files.exists(file) shouldBe false
                }
                Files.exists(spec.workingDirectory.parent) shouldBe false
            }
        }

    @Test
    fun `the process tree is destroyed even after a successful answer`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)

            client(runner).answer()

            runner.lastProcess.destroyed shouldBe true
        }

    @Test
    fun `a request without messages is rejected before starting anything`() =
        runTest {
            val runner = FakeProcessRunner.answering(SUCCESS)

            shouldThrow<IllegalArgumentException> { client(runner).complete(LlmTestData.request(messages = emptyList())) }

            runner.specs.size shouldBe 0
        }

    @Test
    fun `provider and model come from the configuration`() =
        runTest {
            val client = client(FakeProcessRunner.answering(SUCCESS))

            client.provider shouldBe LlmProviderId.CLAUDE_CLI
            client.model shouldBe "claude-haiku-4-5"
        }

    // --- success ----------------------------------------------------------------------------------------------

    @Test
    fun `structured_output is returned with usage, cost and the model that answered`() =
        runTest {
            val response = client(FakeProcessRunner.answering(SUCCESS)).answer()

            response.output shouldBe
                buildJsonObject {
                    put("action", "click")
                    put("ref", 12)
                }
            response.usage shouldBe
                TokenUsage(inputTokens = 1200, outputTokens = 45, cacheReadTokens = 4500, cacheCreationTokens = 300)
            response.costUsd shouldBe 0.0123
            response.model shouldBe "claude-haiku-4-5-20251001"
        }

    @Test
    fun `the configured model is reported when the CLI names none`() =
        runTest {
            val stdout = envelope { putJsonObject("structured_output") { put("action", "click") } }

            client(FakeProcessRunner.answering(stdout)).answer().model shouldBe "claude-haiku-4-5"
        }

    @Test
    fun `JSON in result is used when structured_output is absent`() =
        runTest {
            val stdout = envelope { put("result", """{"action":"click"}""") }

            client(FakeProcessRunner.answering(stdout)).answer().output shouldBe CLICK
        }

    @Test
    fun `a code fence around JSON in result is stripped`() =
        runTest {
            val stdout = envelope { put("result", "```json\n{\"action\": \"click\"}\n```") }

            client(FakeProcessRunner.answering(stdout)).answer().output shouldBe CLICK
        }

    @Test
    fun `a verbose array of messages is read from its result element`() =
        runTest {
            val stdout = """[{"type":"system","subtype":"init"},${envelope { put("result", """{"action":"click"}""") }}]"""

            client(FakeProcessRunner.answering(stdout)).answer().output shouldBe CLICK
        }

    @Test
    fun `log lines printed before the result are ignored`() =
        runTest {
            val stdout = "Warning: something noisy\n${envelope { put("result", """{"action":"click"}""") }}\n"

            client(FakeProcessRunner.answering(stdout)).answer().output shouldBe CLICK
        }

    @Test
    fun `an answer that is not a JSON object is invalid output with the raw text`() =
        runTest {
            val stdout = envelope { put("result", "I clicked the button.") }

            val error = shouldThrow<LlmException.InvalidOutput> { client(FakeProcessRunner.answering(stdout)).answer() }

            error.raw shouldBe "I clicked the button."
            error.message shouldContain "a07/announce"
        }

    // --- failures ---------------------------------------------------------------------------------------------

    @Test
    fun `the verified credit balance error is unavailable with login guidance`() =
        runTest {
            val error =
                shouldThrow<LlmException.Unavailable> {
                    client(FakeProcessRunner.answering(VERIFIED_CREDIT_ERROR, exitCode = 1)).answer()
                }

            error.message shouldContain "Credit balance is too low"
            error.message shouldContain "Run `claude`, then /login with your Claude plan account."
            error.message shouldNotContain "ANTHROPIC_API_KEY"
        }

    @Test
    fun `billing errors explain that an inherited API key overrides the plan login`() =
        runTest {
            val environment = plainEnvironment + ("ANTHROPIC_API_KEY" to "sk-ant-secret")

            val error =
                shouldThrow<LlmException.Unavailable> {
                    client(FakeProcessRunner.answering(VERIFIED_CREDIT_ERROR), environment = environment).answer()
                }

            error.message shouldContain "ANTHROPIC_API_KEY is set"
            error.message shouldNotContain "sk-ant-secret"
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Not logged in · Please run /login",
            "Invalid API key · Please run /login",
            "OAuth token has expired. Please obtain a new token or refresh your existing token.",
            "Failed to authenticate. API Error: 401 authentication_error",
            "Invalid auth token · Fix external API key",
            "Authentication required · Sign in again to continue",
        ],
    )
    fun `login problems are unavailable`(result: String) =
        runTest {
            val stdout = errorEnvelope(result)

            val error = shouldThrow<LlmException.Unavailable> { client(FakeProcessRunner.answering(stdout, exitCode = 1)).answer() }

            error.message shouldContain result
            error.message shouldContain "/login"
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "API Error: Rate limit reached",
            "Claude AI usage limit reached|1760000000",
            "Request rejected (429) · too many requests",
            "You've hit your session limit · resets 5pm (Asia/Baku)",
        ],
    )
    fun `rate and usage limits are rate limited`(result: String) =
        runTest {
            val error =
                shouldThrow<LlmException.RateLimited> { client(FakeProcessRunner.answering(errorEnvelope(result))).answer() }

            error.retryAfter shouldBe null
            error.message shouldContain result
        }

    @Test
    fun `an api error status of 429 is rate limited`() =
        runTest {
            shouldThrow<LlmException.RateLimited> {
                client(FakeProcessRunner.answering(errorEnvelope("API Error", status = 429))).answer()
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 529])
    fun `server errors are transient`(status: Int) =
        runTest {
            val error =
                shouldThrow<LlmException.Transient> {
                    client(FakeProcessRunner.answering(errorEnvelope("API Error: upstream", status = status))).answer()
                }

            error.message shouldContain "HTTP $status"
        }

    @Test
    fun `an overloaded API is transient`() =
        runTest {
            shouldThrow<LlmException.Transient> {
                client(FakeProcessRunner.answering(errorEnvelope("API Error: Overloaded"))).answer()
            }
        }

    @Test
    fun `running out of structured output retries is invalid output`() =
        runTest {
            val stdout =
                envelope {
                    put("subtype", "error_max_structured_output_retries")
                    put("is_error", true)
                    put("result", "")
                }

            shouldThrow<LlmException.InvalidOutput> { client(FakeProcessRunner.answering(stdout)).answer() }
        }

    @Test
    fun `other client errors such as an unknown model are unavailable`() =
        runTest {
            val stdout = errorEnvelope("API Error: 404 model: claude-nonexistent", status = 404)

            val error = shouldThrow<LlmException.Unavailable> { client(FakeProcessRunner.answering(stdout)).answer() }

            error.message shouldContain "claude-nonexistent"
        }

    @Test
    fun `an unexplained error result is transient`() =
        runTest {
            shouldThrow<LlmException.Transient> {
                client(FakeProcessRunner.answering(errorEnvelope("Something went sideways"))).answer()
            }
        }

    @Test
    fun `output that is not JSON is transient and carries the stderr tail`() =
        runTest {
            val stderr = "E".repeat(1_000) + "T".repeat(500)

            val error =
                shouldThrow<LlmException.Transient> {
                    client(FakeProcessRunner.answering("Segmentation fault", stderr = stderr, exitCode = 139)).answer()
                }

            error.message shouldContain "exit code 139"
            error.message shouldContain "T".repeat(500)
            error.message shouldNotContain "EEE"
        }

    @Test
    fun `a CLI that exits without printing anything is transient`() =
        runTest {
            val error = shouldThrow<LlmException.Transient> { client(FakeProcessRunner.answering("")).answer() }

            error.message shouldContain "no output"
        }

    @Test
    fun `a non-zero exit is transient even when a result was printed`() =
        runTest {
            val error =
                shouldThrow<LlmException.Transient> {
                    client(FakeProcessRunner.answering(SUCCESS, stderr = "crashed while exiting", exitCode = 2)).answer()
                }

            error.message shouldContain "code 2"
            error.message shouldContain "crashed while exiting"
        }

    @Test
    fun `an outdated CLI that rejects an option is unavailable with update advice`() =
        runTest {
            val runner = FakeProcessRunner.answering("", stderr = "error: unknown option '--permission-prompts'", exitCode = 1)

            val error = shouldThrow<LlmException.Unavailable> { client(runner).answer() }

            error.message shouldContain "claude update"
        }

    @Test
    fun `a login complaint on stderr without JSON is unavailable`() =
        runTest {
            val runner = FakeProcessRunner.answering("", stderr = "Not logged in · Please run /login", exitCode = 1)

            shouldThrow<LlmException.Unavailable> { client(runner).answer() }
        }

    @Test
    fun `a missing executable is unavailable and names it`() =
        runTest {
            val error = shouldThrow<LlmException.Unavailable> { client(FakeProcessRunner.missingExecutable()).answer() }

            error.message shouldContain "Claude CLI not found: claude"
        }

    @Test
    fun `a CLI that cannot be started for another reason says why`() =
        runTest {
            val runner = FakeProcessRunner { throw IOException("Cannot run program \"claude\": error=7, Argument list too long") }

            val error = shouldThrow<LlmException.Unavailable> { client(runner).answer() }

            error.message shouldContain "Claude CLI could not be started: claude"
            error.message shouldContain "Argument list too long"
        }

    @Test
    fun `a CLI that never answers is killed and reported as a timeout`() =
        runTest {
            val runner = FakeProcessRunner { FakeProcess(hangs = true) }
            val client = client(runner, config = config.copy(timeout = 90.seconds))

            val error = shouldThrow<LlmException.Timeout> { client.answer() }

            currentTime shouldBe 90_000
            error.message shouldContain "a07/announce"
            runner.lastProcess.destroyed shouldBe true
            Files.exists(runner.lastSpec.workingDirectory) shouldBe false
        }

    @Test
    fun `cancelling the caller kills the process`() =
        runTest {
            val runner = FakeProcessRunner { FakeProcess(hangs = true) }
            val client = client(runner)

            val call = launch { client.answer() }
            runCurrent()
            runner.lastProcess.destroyed shouldBe false
            call.cancel()
            call.join()

            runner.lastProcess.destroyed shouldBe true
            Files.exists(runner.lastSpec.workingDirectory) shouldBe false
            currentTime shouldBe 0
        }

    @Test
    fun `invalid configuration is rejected`() {
        shouldThrow<IllegalArgumentException> { ClaudeCliConfig(model = " ") }
        shouldThrow<IllegalArgumentException> { ClaudeCliConfig(executable = "", model = "sonnet") }
        shouldThrow<IllegalArgumentException> { ClaudeCliConfig(model = "sonnet", timeout = 0.seconds) }
        shouldThrow<IllegalArgumentException> { ClaudeCliConfig(model = "sonnet", effort = "") }
    }

    @Test
    fun `the process spec never prints its arguments`() {
        val here = Path.of(".")
        val spec = ProcessSpec(listOf("claude", "--system-prompt", "private prompt"), emptyMap(), here, here, here, here)

        spec.toString() shouldBe "ProcessSpec(executable=claude, args=2)"
    }

    private companion object {
        val CLICK: JsonObject = buildJsonObject { put("action", "click") }

        /** Verbatim shape of a real error run (docs: verified sample), including keys Pətək does not use. */
        const val VERIFIED_CREDIT_ERROR =
            """{"type":"result","subtype":"success","is_error":true,"api_error_status":400,""" +
                """"result":"Credit balance is too low","terminal_reason":"api_error",""" +
                """"session_id":"0b6f5c1e-4f64-4a57-9c3e-2f1f0f7f9a11","total_cost_usd":0,"num_turns":1,""" +
                """"duration_ms":634,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,""" +
                """"cache_read_input_tokens":0,"output_tokens":0,"server_tool_use":{"web_search_requests":0}},""" +
                """"modelUsage":{}}"""

        const val SUCCESS =
            """{"type":"result","subtype":"success","is_error":false,"duration_ms":2310,"num_turns":2,""" +
                """"result":"","structured_output":{"action":"click","ref":12},"session_id":"s-1",""" +
                """"total_cost_usd":0.0123,"usage":{"input_tokens":1200,"cache_creation_input_tokens":300,""" +
                """"cache_read_input_tokens":4500,"output_tokens":45},""" +
                """"modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":1200,"outputTokens":45,"costUSD":0.0123}},""" +
                """"permission_denials":[],"uuid":"u-1"}"""

        fun envelope(fill: JsonObjectBuilder.() -> Unit): String =
            buildJsonObject {
                put("type", "result")
                put("subtype", "success")
                put("is_error", false)
                fill()
            }.toString()

        fun errorEnvelope(
            result: String,
            status: Int? = null,
        ): String =
            envelope {
                put("is_error", true)
                put("result", result)
                if (status != null) put("api_error_status", status)
            }
    }
}
