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
