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
import az.petek.llm.domain.LlmRequest

/**
 * The exact way Pətək calls `claude -p`: an isolated, tool-less, stateless session whose only job is to answer with
 * JSON matching the request schema (docs/ARCHITECTURE.md, Security: no tools, no MCP servers, no settings, no
 * session persistence).
 */
internal object ClaudeCliInvocation {
    /** Kept although it starts with `CLAUDE_CODE_`: it is the user's headless login (`claude setup-token`), not a marker. */
    const val OAUTH_TOKEN_VARIABLE = "CLAUDE_CODE_OAUTH_TOKEN"
    const val API_KEY_VARIABLE = "ANTHROPIC_API_KEY"

    fun command(
        config: ClaudeCliConfig,
        request: LlmRequest,
    ): List<String> =
        buildList {
            add(config.executable)
            add("-p")
            addAll(listOf("--output-format", "json"))
            addAll(listOf("--json-schema", request.responseSchema.toString()))
            addAll(listOf("--model", config.model))
            addAll(listOf("--system-prompt", request.system))
            addAll(listOf("--tools", ""))
            add("--strict-mcp-config")
            addAll(listOf("--setting-sources", ""))
            add("--no-session-persistence")
            addAll(listOf("--permission-prompts", "none"))
            config.effort?.let { addAll(listOf("--effort", it)) }
        }

    /**
     * The inherited environment without the markers of an enclosing Claude Code session (`CLAUDECODE`,
     * `CLAUDE_CODE_*`): with them the child believes it is nested inside another session and misbehaves.
     */
    fun environment(inherited: Map<String, String>): Map<String, String> = inherited.filterKeys { !isSessionMarker(it) }

    private fun isSessionMarker(name: String): Boolean =
        name == "CLAUDECODE" || (name.startsWith("CLAUDE_CODE_") && name != OAUTH_TOKEN_VARIABLE)

    /** STDIN text, see [CliTranscripts.of]. */
    fun transcript(messages: List<LlmMessage>): String = CliTranscripts.of(messages)
}
