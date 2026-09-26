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

/**
 * The command-line AI agents Pətək can drive, each with the user's own login: the only public way to create one (the
 * profiles are internal). [generic] runs any other tool from the owner's configuration, so no vendor is required.
 */
object CliAgents {
    /** Provider keys this factory serves. */
    val PROVIDERS: Set<LlmProviderKey> =
        setOf(LlmProviderKey.CODEX_CLI, LlmProviderKey.GEMINI_CLI, LlmProviderKey.OPENCODE_CLI, LlmProviderKey.CLI)

    /** Default executable names, looked up on `PATH`. */
    val DEFAULT_EXECUTABLES: Map<LlmProviderKey, String> =
        mapOf(
            LlmProviderKey.CODEX_CLI to "codex",
            LlmProviderKey.GEMINI_CLI to "gemini",
            LlmProviderKey.OPENCODE_CLI to "opencode",
        )

    fun codex(config: CliAgentConfig): LlmClient = CliAgentLlmClient(CodexCliProfile(config), config.timeout)

    fun gemini(config: CliAgentConfig): LlmClient = CliAgentLlmClient(GeminiCliProfile(config), config.timeout)

    fun openCode(config: CliAgentConfig): LlmClient = CliAgentLlmClient(OpenCodeCliProfile(config), config.timeout)

    /** Any tool, as `PETEK_LLM_BIN` and `PETEK_LLM_ARGS` describe it. */
    fun generic(config: CliAgentConfig): LlmClient = CliAgentLlmClient(GenericCliProfile(config), config.timeout)

    /** The client for [provider] (one of [PROVIDERS]), or null for a key this factory does not serve. */
    fun create(
        provider: LlmProviderKey,
        config: CliAgentConfig,
    ): LlmClient? =
        when (provider) {
            LlmProviderKey.CODEX_CLI -> codex(config)
            LlmProviderKey.GEMINI_CLI -> gemini(config)
            LlmProviderKey.OPENCODE_CLI -> openCode(config)
            LlmProviderKey.CLI -> generic(config)
            else -> null
        }
}
