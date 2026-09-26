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

package az.petek.app.di

import az.petek.app.config.PetekConfig
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.infrastructure.api.AnthropicApiConfig
import az.petek.llm.infrastructure.api.AnthropicApiLlmClient
import az.petek.llm.infrastructure.cli.ClaudeCliConfig
import az.petek.llm.infrastructure.cli.ClaudeCliLlmClient
import az.petek.llm.infrastructure.cli.CliAgentConfig
import az.petek.llm.infrastructure.cli.CliAgents
import az.petek.llm.infrastructure.http.OpenAiCompatibleConfig
import az.petek.llm.infrastructure.http.OpenAiCompatibleLlmClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Creates the bare provider client selected by `PETEK_LLM_PROVIDER` (no retries, limits or metering: the container
 * adds those decorators). The caller owns the result and must close it when it is [AutoCloseable].
 *
 * Providers are a registry keyed by [LlmProviderKey], not an exhaustive `when`: a new provider is one more entry
 * (open/closed).
 */
object LlmProviders {
    /** One agent decision may take a while with thinking models; the CLI kills the process tree after this. */
    val AGENT_CALL_TIMEOUT: Duration = 240.seconds

    /** `petek doctor` waits at most this long for its single test call. */
    val DOCTOR_CALL_TIMEOUT: Duration = 90.seconds

    private val REGISTRY: Map<LlmProviderKey, (PetekConfig, Duration) -> LlmClient> =
        linkedMapOf(
            LlmProviderKey.CLAUDE_CLI to { config, timeout ->
                ClaudeCliLlmClient(
                    ClaudeCliConfig(
                        executable = config.effectiveLlmBin,
                        model = checkNotNull(config.effectiveLlmModel),
                        timeout = timeout,
                        effort = config.effectiveLlmEffort,
                    ),
                )
            },
            LlmProviderKey.ANTHROPIC_API to { config, timeout ->
                val apiKey = checkNotNull(config.llmApiKey) { "the anthropic-api provider needs ANTHROPIC_API_KEY" }
                AnthropicApiLlmClient(
                    AnthropicApiConfig(apiKey = apiKey, model = checkNotNull(config.effectiveLlmModel), timeout = timeout),
                )
            },
            LlmProviderKey.CODEX_CLI to { config, timeout -> CliAgents.codex(cliConfig(config, timeout)) },
            LlmProviderKey.GEMINI_CLI to { config, timeout -> CliAgents.gemini(cliConfig(config, timeout)) },
            LlmProviderKey.OPENCODE_CLI to { config, timeout -> CliAgents.openCode(cliConfig(config, timeout)) },
            LlmProviderKey.OPENAI_COMPAT to { config, timeout ->
                OpenAiCompatibleLlmClient(
                    OpenAiCompatibleConfig(
                        baseUrl = checkNotNull(config.llmBaseUrl) { "the openai-compat provider needs PETEK_LLM_BASE_URL" },
                        model = checkNotNull(config.effectiveLlmModel) { "the openai-compat provider needs PETEK_LLM_MODEL" },
                        apiKey = config.llmApiKey,
                        structured = config.llmStructured,
                        timeout = timeout,
                        effort = config.llmEffort,
                    ),
                )
            },
        )

    /** Every provider `PETEK_LLM_PROVIDER` may name besides `auto`. */
    val KEYS: Set<LlmProviderKey> = REGISTRY.keys

    /** Providers that run a CLI binary (so `doctor` can ask it for `--version`). */
    val CLI_PROVIDERS: Set<LlmProviderKey> = PetekConfig.DEFAULT_BINARIES.keys

    fun create(
        config: PetekConfig,
        timeout: Duration = AGENT_CALL_TIMEOUT,
    ): LlmClient {
        val factory = REGISTRY[config.llmProvider] ?: error("no LLM provider is registered as ${config.llmProvider}")
        return factory(config, timeout)
    }

    private fun cliConfig(
        config: PetekConfig,
        timeout: Duration,
    ) = CliAgentConfig(
        executable = config.effectiveLlmBin,
        model = config.effectiveLlmModel,
        timeout = timeout,
        effort = config.effectiveLlmEffort,
    )
}
