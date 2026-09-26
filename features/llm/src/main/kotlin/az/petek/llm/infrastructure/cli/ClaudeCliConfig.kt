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
 * How to run Claude Code headless.
 *
 * @property executable command name (resolved on `PATH`) or absolute path of the `claude` binary.
 * @property model passed as `--model` (an alias such as `sonnet` or a full model id).
 * @property timeout wall-clock limit for one call; the process tree is killed when it is exceeded.
 * @property effort passed as `--effort` when not null; `low` keeps agent decisions fast and cheap.
 */
data class ClaudeCliConfig(
    val executable: String = "claude",
    val model: String,
    val timeout: Duration = 180.seconds,
    val effort: String? = "low",
) {
    init {
        require(executable.isNotBlank()) { "Claude CLI executable must not be blank" }
        require(model.isNotBlank()) { "Claude CLI model must not be blank" }
        require(timeout.isPositive()) { "Claude CLI timeout must be positive, was $timeout" }
        require(effort == null || effort.isNotBlank()) { "Claude CLI effort must be null or a level such as 'low'" }
    }
}
