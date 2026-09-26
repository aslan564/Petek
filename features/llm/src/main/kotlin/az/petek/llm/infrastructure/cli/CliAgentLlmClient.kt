/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.cli

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * [LlmClient] that runs a coding-agent CLI headless, one process per call, with the user's own login for that agent;
 * [profile] says how the agent is called and how its output reads.
 *
 * Every call is isolated: a fresh empty working directory, files standing in for the standard streams next to it
 * (outside what the agent sees), the argument vector passed without a shell. Blocking process I/O runs on
 * [ioDispatcher]; exceeding [timeout] or cancelling the caller kills the whole process tree, also on success nothing
 * the agent started outlives the call.
 */
internal class CliAgentLlmClient(
    private val profile: CliAgentProfile,
    private val timeout: Duration,
    private val runner: ProcessRunner = SystemProcessRunner(),
    private val inheritedEnvironment: () -> Map<String, String> = System::getenv,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmClient {
    init {
        require(timeout.isPositive()) { "${profile.displayName} timeout must be positive, was $timeout" }
    }

    override val provider: LlmProviderKey get() = profile.provider
    override val model: String get() = profile.model

    override suspend fun complete(request: LlmRequest): LlmResponse {
        require(request.messages.isNotEmpty()) { "LLM request ${request.label} has no messages" }
        val environment = profile.environment(inheritedEnvironment())
        val response =
            withContext(ioDispatcher) {
                withScratchDirectory { scratch ->
                    val call = profile.call(request, scratch)
                    val spec = prepare(scratch, call, environment)
                    profile.parse(execute(spec, request.label), scratch, environment, request.label)
                }
            }
        logger.debug {
            "${profile.displayName} answered ${request.label} (model=${response.model}, " +
                "in=${response.usage.inputTokens}, out=${response.usage.outputTokens}, cost=${response.costUsd})"
        }
        return response
    }

    private fun prepare(
        scratch: Path,
        call: CliCall,
        environment: Map<String, String>,
    ): ProcessSpec =
        try {
            ProcessSpec(
                command = call.command,
                environment = environment,
                workingDirectory = Files.createDirectory(scratch.resolve(WORK_DIRECTORY)),
                stdinFile = Files.writeString(scratch.resolve(STDIN_FILE), call.stdin),
                stdoutFile = scratch.resolve(STDOUT_FILE),
                stderrFile = scratch.resolve(STDERR_FILE),
            )
        } catch (e: IOException) {
            throw LlmException.Transient("Cannot prepare a working directory for the ${profile.displayName}: ${e.message}", e)
        }

    private suspend fun execute(
        spec: ProcessSpec,
        label: String,
    ): ProcessOutput {
        val process =
            try {
                runner.start(spec)
            } catch (e: IOException) {
                throw notStarted(spec.command.first(), e)
            }
        val exitCode =
            try {
                withTimeoutOrNull(timeout) { process.awaitExit() }
                    ?: throw LlmException.Timeout("${profile.displayName} did not answer within $timeout for $label")
            } finally {
                process.destroyTree()
            }
        return try {
            ProcessOutput(
                exitCode = exitCode,
                stdout = OutputFiles.head(spec.stdoutFile, MAX_STDOUT_BYTES).decodeToString(),
                stderr = OutputFiles.tail(spec.stderrFile, MAX_STDERR_BYTES).decodeToString(),
            )
        } catch (e: IOException) {
            throw LlmException.Transient("Cannot read the output of the ${profile.displayName} for $label: ${e.message}", e)
        }
    }

    /** A missing binary is the common case; anything else (not executable, argument list too long) says so. */
    private fun notStarted(
        executable: String,
        error: IOException,
    ): LlmException.Unavailable {
        val reason = error.message.orEmpty()
        val summary =
            if (MISSING_EXECUTABLE_MARKERS.any { it in reason }) {
                "${profile.displayName} not found: $executable"
            } else {
                "${profile.displayName} could not be started: $executable"
            }
        return LlmException.Unavailable("$summary ($reason)", error)
    }

    private inline fun <T> withScratchDirectory(block: (Path) -> T): T {
        val directory =
            try {
                Files.createTempDirectory(profile.scratchPrefix)
            } catch (e: IOException) {
                throw LlmException.Transient("Cannot create a working directory for the ${profile.displayName}: ${e.message}", e)
            }
        try {
            return block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val WORK_DIRECTORY = "work"
        const val STDIN_FILE = "stdin.txt"
        const val STDOUT_FILE = "stdout.json"
        const val STDERR_FILE = "stderr.txt"
        const val MAX_STDOUT_BYTES = 16 * 1024 * 1024
        const val MAX_STDERR_BYTES = 16 * 1024
        val MISSING_EXECUTABLE_MARKERS = listOf("error=2,", "No such file")
    }
}
