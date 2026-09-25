package az.petek.llm.infrastructure.cli

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderId
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * [LlmClient] that runs Claude Code headless (`claude -p`) with the user's Claude plan login, one process per call.
 *
 * Every call is isolated: no tools, no MCP servers, no settings files, no saved session, a fresh empty working
 * directory, and an environment without the markers of an enclosing Claude Code session (see [ClaudeCliInvocation]).
 * The prompt goes through STDIN, never through a shell. Blocking process I/O runs on [Dispatchers.IO]; exceeding
 * [ClaudeCliConfig.timeout] or cancelling the caller kills the whole process tree.
 *
 * Failures map to [LlmException]: not logged in / no credit / CLI missing -> `Unavailable` (with the fix in the
 * message), rate or usage limits -> `RateLimited`, overload, 5xx, crashes and unreadable output -> `Transient`,
 * an answer that is not a JSON object -> `InvalidOutput`, too slow -> `Timeout`.
 */
class ClaudeCliLlmClient internal constructor(
    private val config: ClaudeCliConfig,
    private val runner: ProcessRunner,
    private val inheritedEnvironment: () -> Map<String, String>,
    private val ioDispatcher: CoroutineDispatcher,
) : LlmClient {
    constructor(
        config: ClaudeCliConfig,
        runner: ProcessRunner = SystemProcessRunner(),
    ) : this(config, runner, System::getenv, Dispatchers.IO)

    override val provider: LlmProviderId = LlmProviderId.CLAUDE_CLI
    override val model: String = config.model

    override suspend fun complete(request: LlmRequest): LlmResponse {
        require(request.messages.isNotEmpty()) { "LLM request ${request.label} has no messages" }
        val environment = ClaudeCliInvocation.environment(inheritedEnvironment())
        val parser =
            ClaudeCliResultParser(
                configuredModel = config.model,
                apiKeyInEnvironment = !environment[ClaudeCliInvocation.API_KEY_VARIABLE].isNullOrBlank(),
            )
        val command = ClaudeCliInvocation.command(config, request)
        val prompt = ClaudeCliInvocation.transcript(request.messages)
        val response =
            withContext(ioDispatcher) {
                withScratchDirectory { directory ->
                    val output = execute(ProcessSpec(command, environment, directory), prompt, request.label)
                    parser.parse(output, request.label)
                }
            }
        logger.debug {
            "Claude CLI answered ${request.label} (model=${response.model}, " +
                "in=${response.usage.inputTokens}, out=${response.usage.outputTokens}, cost=${response.costUsd})"
        }
        return response
    }

    private suspend fun execute(
        spec: ProcessSpec,
        prompt: String,
        label: String,
    ): ProcessOutput {
        val process =
            try {
                runner.start(spec)
            } catch (e: IOException) {
                throw notStarted(e)
            }
        return coroutineScope {
            val stdout = async { StreamDrain.head(process.stdout, MAX_STDOUT_BYTES) }
            val stderr = async { StreamDrain.tail(process.stderr, MAX_STDERR_BYTES) }
            launch { feed(process.stdin, prompt) }
            try {
                withTimeoutOrNull(config.timeout) {
                    val exitCode = process.awaitExit()
                    ProcessOutput(exitCode, stdout.await().decodeToString(), stderr.await().decodeToString())
                } ?: throw LlmException.Timeout("Claude CLI did not answer within ${config.timeout} for $label")
            } finally {
                // Also on success: nothing the CLI started may outlive the call. Killing unblocks the pipe readers,
                // which this scope then waits for.
                process.destroyTree()
            }
        }
    }

    /** A missing binary is the common case; anything else (not executable, argument list too long) says so. */
    private fun notStarted(error: IOException): LlmException.Unavailable {
        val reason = error.message.orEmpty()
        val summary =
            if (MISSING_EXECUTABLE_MARKERS.any { it in reason }) {
                "Claude CLI not found: ${config.executable}"
            } else {
                "Claude CLI could not be started: ${config.executable}"
            }
        return LlmException.Unavailable("$summary ($reason)", error)
    }

    private fun feed(
        stdin: OutputStream,
        prompt: String,
    ) {
        try {
            stdin.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) }
        } catch (_: IOException) {
            // The CLI exited before reading everything (e.g. bad arguments); its output explains why.
        }
    }

    private inline fun <T> withScratchDirectory(block: (Path) -> T): T {
        val directory =
            try {
                Files.createTempDirectory(SCRATCH_PREFIX)
            } catch (e: IOException) {
                throw LlmException.Transient("Cannot create a working directory for the Claude CLI: ${e.message}", e)
            }
        try {
            return block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val SCRATCH_PREFIX = "petek-claude-"
        const val MAX_STDOUT_BYTES = 16 * 1024 * 1024
        const val MAX_STDERR_BYTES = 16 * 1024
        val MISSING_EXECUTABLE_MARKERS = listOf("error=2,", "No such file")
    }
}
