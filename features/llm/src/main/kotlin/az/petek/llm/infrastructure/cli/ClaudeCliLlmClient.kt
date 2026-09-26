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

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * [LlmClient] that runs Claude Code headless (`claude -p`) with the user's Claude plan login, one process per call:
 * [CliAgentLlmClient] with the Claude profile.
 *
 * Every call is isolated: no tools, no MCP servers, no settings files, no saved session, a fresh empty working
 * directory, and an environment without the markers of an enclosing Claude Code session (see [ClaudeCliInvocation]).
 *
 * Failures map to [az.petek.llm.domain.LlmException]: not logged in / no credit / CLI missing -> `Unavailable` (with
 * the fix in the message), rate or usage limits -> `RateLimited`, overload, 5xx, crashes and unreadable output ->
 * `Transient`, an answer that is not a JSON object -> `InvalidOutput`, too slow -> `Timeout`.
 */
class ClaudeCliLlmClient internal constructor(
    config: ClaudeCliConfig,
    runner: ProcessRunner,
    inheritedEnvironment: () -> Map<String, String>,
    ioDispatcher: CoroutineDispatcher,
) : LlmClient by CliAgentLlmClient(ClaudeCliProfile(config), config.timeout, runner, inheritedEnvironment, ioDispatcher) {
    constructor(
        config: ClaudeCliConfig,
        runner: ProcessRunner = SystemProcessRunner(),
    ) : this(config, runner, System::getenv, Dispatchers.IO)
}

/** `claude -p` with native structured output (`--json-schema`). */
internal class ClaudeCliProfile(
    private val config: ClaudeCliConfig,
) : CliAgentProfile {
    override val provider: LlmProviderKey = LlmProviderKey.CLAUDE_CLI
    override val model: String = config.model
    override val displayName: String = "Claude CLI"
    override val scratchPrefix: String = "petek-claude-"

    override fun call(
        request: LlmRequest,
        scratch: Path,
    ): CliCall = CliCall(ClaudeCliInvocation.command(config, request), ClaudeCliInvocation.transcript(request.messages))

    override fun environment(inherited: Map<String, String>): Map<String, String> = ClaudeCliInvocation.environment(inherited)

    override fun parse(
        output: ProcessOutput,
        scratch: Path,
        environment: Map<String, String>,
        label: String,
    ): LlmResponse =
        ClaudeCliResultParser(
            configuredModel = config.model,
            apiKeyInEnvironment = !environment[ClaudeCliInvocation.API_KEY_VARIABLE].isNullOrBlank(),
        ).parse(output, label)
}
