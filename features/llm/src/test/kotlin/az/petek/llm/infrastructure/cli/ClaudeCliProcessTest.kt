package az.petek.llm.infrastructure.cli

import az.petek.llm.LlmTestData
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs a tiny shell script in place of `claude` through the real [SystemProcessRunner], to prove the process
 * handling (argument vector, environment, stdin, working directory, tree kill) end to end without a real CLI.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class ClaudeCliProcessTest {
    @TempDir
    lateinit var temp: Path

    private val inherited: Map<String, String> =
        System.getenv().filterKeys { !it.startsWith("CLAUDE") } +
            mapOf("CLAUDECODE" to "1", "CLAUDE_CODE_ENTRYPOINT" to "cli", "PETEK_PROBE" to "kept")

    private fun script(body: String): Path {
        val file = temp.resolve("fake-claude")
        file.writeText("#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        return file
    }

    private fun client(
        executable: Path,
        timeoutMillis: Long = 10_000,
    ) = ClaudeCliLlmClient(
        ClaudeCliConfig(executable = executable.toString(), model = "claude-haiku-4-5", timeout = timeoutMillis.milliseconds),
        SystemProcessRunner(),
        { inherited },
        Dispatchers.IO,
    )

    @Test
    fun `a real process receives the arguments, environment and stdin and its JSON answer is parsed`() {
        val out = temp.resolve("out").also { Files.createDirectories(it) }
        val executable =
            script(
                """
                printf '%s\0' "${'$'}@" > "$out/args"
                env > "$out/env"
                pwd > "$out/pwd"
                cat > "$out/stdin"
                echo '$CANNED'
                """.trimIndent(),
            )
        val request =
            LlmTestData.request(
                messages = listOf(LlmMessage(LlmRole.USER, "Elan: \"Sabah iclas\" \$HOME `id` ; rm -rf /")),
                system = "Sistem: ə, ş, ğ; \$PATH is not expanded",
            )

        val response = runBlocking { client(executable).complete(request) }

        response.output shouldBe buildJsonObject { put("action", "click") }
        response.costUsd shouldBe 0.002
        response.model shouldBe "claude-haiku-4-5"

        val args =
            out
                .resolve("args")
                .readText()
                .split('\u0000')
                .dropLast(1)
        args shouldBe ClaudeCliInvocation.command(ClaudeCliConfig(executable = "x", model = "claude-haiku-4-5"), request).drop(1)
        out.resolve("stdin").readText() shouldBe "Elan: \"Sabah iclas\" \$HOME `id` ; rm -rf /"

        val environment = out.resolve("env").readText().lines()
        environment shouldContain "PETEK_PROBE=kept"
        environment.none { it.startsWith("CLAUDECODE=") || it.startsWith("CLAUDE_CODE_ENTRYPOINT=") } shouldBe true

        val workingDirectory = Path.of(out.resolve("pwd").readText().trim())
        workingDirectory.parent.fileName.toString() shouldStartWith "petek-claude-"
        workingDirectory.parent.exists() shouldBe false
    }

    @Test
    fun `a background child that outlives the CLI does not hold the answer back`() {
        val childPid = temp.resolve("child.pid")
        // The child inherits the CLI's STDOUT and keeps it open for a minute after the CLI itself has exited.
        val executable =
            script(
                """
                cat > /dev/null
                sleep 60 &
                echo ${'$'}! > "$childPid"
                echo '$CANNED'
                """.trimIndent(),
            )
        try {
            val response = runBlocking { client(executable, timeoutMillis = 10_000).complete(LlmTestData.request()) }

            response.output shouldBe buildJsonObject { put("action", "click") }
        } finally {
            ProcessHandle.of(childPid.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `a real process that never answers is killed together with its children`() {
        val childPid = temp.resolve("child.pid")
        val executable =
            script(
                """
                sleep 60 &
                echo ${'$'}! > "$childPid"
                wait
                """.trimIndent(),
            )

        val error =
            shouldThrow<LlmException.Timeout> { runBlocking { client(executable, timeoutMillis = 1_500).complete(LlmTestData.request()) } }

        error.message shouldContain "a07/announce"
        val child = ProcessHandle.of(childPid.readText().trim().toLong())
        child.ifPresent { it.onExit().get(10, TimeUnit.SECONDS) }
        child.map { it.isAlive }.orElse(false) shouldBe false
    }

    @Test
    fun `a real process reporting an error is mapped like the CLI would be`() {
        val executable = script("cat > /dev/null\necho '$VERIFIED_CREDIT_ERROR'\nexit 1")

        val error = shouldThrow<LlmException.Unavailable> { runBlocking { client(executable).complete(LlmTestData.request()) } }

        error.message shouldContain "Credit balance is too low"
    }

    @Test
    fun `a missing executable is unavailable`() {
        val missing = temp.resolve("no-such-claude")

        val error = shouldThrow<LlmException.Unavailable> { runBlocking { client(missing).complete(LlmTestData.request()) } }

        error.message shouldContain "Claude CLI not found: $missing"
    }

    @Test
    fun `an executable without execute permission is unavailable with the reason`() {
        val executable = script("echo never")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rw-------"))

        val error = shouldThrow<LlmException.Unavailable> { runBlocking { client(executable).complete(LlmTestData.request()) } }

        error.message shouldContain "Claude CLI could not be started: $executable"
    }

    private companion object {
        const val CANNED =
            """{"type":"result","subtype":"success","is_error":false,"result":"",""" +
                """"structured_output":{"action":"click"},"total_cost_usd":0.002,""" +
                """"usage":{"input_tokens":10,"output_tokens":5},"modelUsage":{}}"""

        const val VERIFIED_CREDIT_ERROR =
            """{"type":"result","subtype":"success","is_error":true,"api_error_status":400,""" +
                """"result":"Credit balance is too low","terminal_reason":"api_error","modelUsage":{}}"""
    }
}
