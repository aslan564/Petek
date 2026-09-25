/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.di

import az.petek.app.config.PetekConfig
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmProviderId
import az.petek.llm.infrastructure.api.AnthropicApiConfig
import az.petek.llm.infrastructure.api.AnthropicApiLlmClient
import az.petek.llm.infrastructure.cli.ClaudeCliConfig
import az.petek.llm.infrastructure.cli.ClaudeCliLlmClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Creates the bare provider client selected by `PETEK_LLM_PROVIDER` (no retries, limits or metering: the container
 * adds those decorators). The caller owns the result and must close it when it is [AutoCloseable].
 */
object LlmProviders {
    /** One agent decision may take a while with thinking models; the CLI kills the process tree after this. */
    val AGENT_CALL_TIMEOUT: Duration = 240.seconds

    /** `petek doctor` waits at most this long for its single test call. */
    val DOCTOR_CALL_TIMEOUT: Duration = 90.seconds

    /** Keeps agent decisions fast and cheap; the model is effort-controllable. */
    const val EFFORT = "low"

    fun create(
        config: PetekConfig,
        timeout: Duration = AGENT_CALL_TIMEOUT,
    ): LlmClient =
        when (config.llmProvider) {
            LlmProviderId.CLAUDE_CLI -> {
                ClaudeCliLlmClient(
                    ClaudeCliConfig(executable = config.claudeBin, model = config.llmModel, timeout = timeout, effort = EFFORT),
                )
            }

            LlmProviderId.ANTHROPIC_API -> {
                val apiKey = checkNotNull(config.anthropicApiKey) { "the anthropic-api provider needs ANTHROPIC_API_KEY" }
                AnthropicApiLlmClient(AnthropicApiConfig(apiKey = apiKey, model = config.llmModel, timeout = timeout))
            }
        }
}
