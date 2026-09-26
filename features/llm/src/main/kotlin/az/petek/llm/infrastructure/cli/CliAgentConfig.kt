/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.cli

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How to run a coding-agent CLI other than Claude headless (`codex`, `gemini`, `opencode`).
 *
 * @property executable command name (resolved on `PATH`) or absolute path of the binary (`PETEK_LLM_BIN`).
 * @property model passed to the CLI when set; null keeps the model the CLI is configured for.
 * @property timeout wall-clock limit for one call; the process tree is killed when it is exceeded.
 * @property effort reasoning effort, passed only to a CLI that supports it (`PETEK_LLM_EFFORT`).
 */
data class CliAgentConfig(
    val executable: String,
    val model: String? = null,
    val timeout: Duration = 180.seconds,
    val effort: String? = null,
) {
    init {
        require(executable.isNotBlank()) { "CLI executable must not be blank" }
        require(model == null || model.isNotBlank()) { "CLI model must be null or a name" }
        require(timeout.isPositive()) { "CLI timeout must be positive, was $timeout" }
        require(effort == null || effort.isNotBlank()) { "CLI effort must be null or a level such as 'low'" }
    }

    /** The name shown in usage and errors when the CLI's own default model answers. */
    val modelLabel: String get() = model ?: DEFAULT_MODEL_LABEL

    companion object {
        const val DEFAULT_MODEL_LABEL = "default"
    }
}
