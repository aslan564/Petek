package az.petek.llm.infrastructure.cli

import kotlinx.coroutines.future.await

/**
 * [ProcessRunner] backed by [ProcessBuilder]: argument vector (no shell), explicit environment and directory, and
 * the standard streams redirected to the files named in the [ProcessSpec].
 */
class SystemProcessRunner : ProcessRunner {
    override fun start(spec: ProcessSpec): RunningProcess {
        val builder =
            ProcessBuilder(spec.command)
                .directory(spec.workingDirectory.toFile())
                .redirectInput(spec.stdinFile.toFile())
                .redirectOutput(spec.stdoutFile.toFile())
                .redirectError(spec.stderrFile.toFile())
        builder.environment().apply {
            clear()
            putAll(spec.environment)
        }
        return SystemRunningProcess(builder.start())
    }

    private class SystemRunningProcess(
        private val process: Process,
    ) : RunningProcess {
        override suspend fun awaitExit(): Int = process.onExit().await().exitValue()

        override fun destroyTree() {
            // Snapshot first: once the parent is gone its children are re-parented and no longer its descendants.
            val descendants = process.descendants().toList()
            process.destroyForcibly()
            descendants.forEach { it.destroyForcibly() }
        }
    }
}
