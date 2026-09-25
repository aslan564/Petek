/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.cli

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
