/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.cli

import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import java.nio.file.Path

/**
 * How one coding-agent CLI is called in headless mode: the part of a call that differs between `claude -p`,
 * `codex exec`, `gemini -p` and `opencode run`. Everything else (scratch directory, timeout, killing the process tree,
 * bounded output files, "never a shell") is [CliAgentLlmClient]'s and the same for every agent (strategy pattern).
 */
internal interface CliAgentProfile {
    val provider: LlmProviderKey

    /** The model named in errors and usage; the CLI's own default when the owner configured none. */
    val model: String

    /** How errors name the agent, e.g. `Claude CLI`. */
    val displayName: String

    /** Prefix of the per-call temporary directory, e.g. `petek-claude-`. */
    val scratchPrefix: String

    /**
     * The argument vector and STDIN text of one call. Files the call needs (a schema file, an output file) go into
     * [scratch], never into the empty working directory the agent sees.
     */
    fun call(
        request: LlmRequest,
        scratch: Path,
    ): CliCall

    /** The child's complete environment, derived from the inherited one (e.g. without an enclosing session's markers). */
    fun environment(inherited: Map<String, String>): Map<String, String> = inherited

    /** Turns what the process left behind into a response or the [az.petek.llm.domain.LlmException] to throw. */
    fun parse(
        output: ProcessOutput,
        scratch: Path,
        environment: Map<String, String>,
        label: String,
    ): LlmResponse
}

/** One call's argument vector ([command], first element the executable) and the text written to its STDIN. */
internal data class CliCall(
    val command: List<String>,
    val stdin: String,
) {
    /** Arguments may carry prompts; the executable name is enough for logs and errors. */
    override fun toString(): String = "CliCall(executable=${command.firstOrNull()}, args=${command.size - 1})"
}

/** Transcript helpers shared by the profiles. */
internal object CliTranscripts {
    /**
     * STDIN text: a lone user message as-is; a conversation as a plain transcript the model can continue
     * (`USER:\n...\n\nASSISTANT:\n...`).
     */
    fun of(messages: List<LlmMessage>): String {
        val single = messages.singleOrNull()
        if (single != null && single.role == LlmRole.USER) return single.content
        return messages.joinToString(separator = "\n\n") { "${it.role.name}:\n${it.content}" }
    }

    /** For agents without a system-prompt flag: the system text first, then the transcript. */
    fun withSystem(
        system: String,
        messages: List<LlmMessage>,
    ): String = "SYSTEM:\n$system\n\n${of(messages)}"
}
