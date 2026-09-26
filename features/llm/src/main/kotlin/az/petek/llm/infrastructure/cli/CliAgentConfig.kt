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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How to run a command-line AI agent headless: a known one (`codex`, `gemini`, `opencode`) or any other the owner
 * describes ([arguments]).
 *
 * @property executable command name (resolved on `PATH`) or absolute path of the binary (`PETEK_LLM_BIN`).
 * @property model passed to the CLI when set; null keeps the model the CLI is configured for.
 * @property timeout wall-clock limit for one call; the process tree is killed when it is exceeded.
 * @property effort reasoning effort, passed only to a CLI that supports it (`PETEK_LLM_EFFORT`).
 * @property arguments the argument template of a tool Pətək does not know by name (`PETEK_LLM_ARGS`, see
 *   [GenericCliProfile]); the known agents ignore it.
 * @property unsetEnvironment variables removed for the child, `NAME` or `PREFIX*` (`PETEK_LLM_ENV_UNSET`).
 */
data class CliAgentConfig(
    val executable: String,
    val model: String? = null,
    val timeout: Duration = 180.seconds,
    val effort: String? = null,
    val arguments: List<String> = emptyList(),
    val unsetEnvironment: List<String> = emptyList(),
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
