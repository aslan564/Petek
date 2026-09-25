package az.petek.llm.infrastructure.cli

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/**
 * Starts operating-system processes. This seam lets [ClaudeCliLlmClient] be tested with a fake process, and keeps the
 * "argument list, never a shell" rule in one place ([SystemProcessRunner]).
 */
fun interface ProcessRunner {
    /** @throws java.io.IOException when the executable cannot be started (missing, not executable). */
    fun start(spec: ProcessSpec): RunningProcess
}

/**
 * What to start. [environment] is the COMPLETE environment of the child (nothing else is inherited), and
 * [command] is passed to the OS as an argument vector, so no value is ever interpreted by a shell.
 */
data class ProcessSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
) {
    init {
        require(command.isNotEmpty()) { "command must name an executable" }
    }

    /** Arguments may carry prompts; the executable name is enough for logs and errors. */
    override fun toString(): String = "ProcessSpec(executable=${command.first()}, args=${command.size - 1})"
}

/** A started process. Implementations must make [destroyTree] safe to call more than once and after exit. */
interface RunningProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream

    /** Suspends until the process exits and returns its exit code. Cancelling the caller does not kill the process. */
    suspend fun awaitExit(): Int

    /** Forcibly stops the process and every descendant it started (so no orphan keeps running or holds a pipe). */
    fun destroyTree()
}
