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

import java.nio.file.Path

/**
 * Starts operating-system processes. This seam lets [CliAgentLlmClient] be tested with a fake process, and keeps the
 * "argument list, never a shell" rule in one place ([SystemProcessRunner]).
 */
fun interface ProcessRunner {
    /** @throws java.io.IOException when the executable cannot be started (missing, not executable). */
    fun start(spec: ProcessSpec): RunningProcess
}

/**
 * What to start. [environment] is the COMPLETE environment of the child (nothing else is inherited), and
 * [command] is passed to the OS as an argument vector, so no value is ever interpreted by a shell.
 *
 * The standard streams are files, not pipes: STDIN is read from [stdinFile] (written before the start), STDOUT and
 * STDERR go to [stdoutFile] and [stderrFile]. A pipe stays open for as long as ANY process holding it lives, so a
 * background child that outlived the CLI would keep a pipe reader (and the call) blocked past its timeout; a file
 * is complete the moment the process exits.
 */
data class ProcessSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
    val stdinFile: Path,
    val stdoutFile: Path,
    val stderrFile: Path,
) {
    init {
        require(command.isNotEmpty()) { "command must name an executable" }
    }

    /** Arguments may carry prompts; the executable name is enough for logs and errors. */
    override fun toString(): String = "ProcessSpec(executable=${command.first()}, args=${command.size - 1})"
}

/** A started process. Implementations must make [destroyTree] safe to call more than once and after exit. */
interface RunningProcess {
    /** Suspends until the process exits and returns its exit code. Cancelling the caller does not kill the process. */
    suspend fun awaitExit(): Int

    /** Forcibly stops the process and every descendant it started (so no orphan keeps running). */
    fun destroyTree()
}
