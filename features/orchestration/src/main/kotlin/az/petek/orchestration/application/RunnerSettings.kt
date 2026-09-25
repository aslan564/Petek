package az.petek.orchestration.application

import java.nio.file.Path

/**
 * Environment-dependent settings of [DefaultCampaignRunner] (they come from `.env`, not from the campaign).
 *
 * @property mailDomain catch-all test domain used for every generated e-mail, e.g. `test.kadrohr.com`.
 * @property storageRoot directory for per-agent browser storage state: `<storageRoot>/<runId>/<agentId>.json`.
 * @property activatingRunFunctions `run` functions that log an agent in; a successful setup step running one of them
 *   (or any successful setup `do` step, which is how a UI sign-up is written) marks the identity ACTIVE.
 */
data class RunnerSettings(
    val mailDomain: String,
    val storageRoot: Path,
    val activatingRunFunctions: Set<String> = DEFAULT_ACTIVATING_RUN_FUNCTIONS,
) {
    init {
        require(mailDomain.isNotBlank()) { "mailDomain must not be blank" }
    }

    companion object {
        val DEFAULT_ACTIVATING_RUN_FUNCTIONS: Set<String> = setOf("register_owner", "register_and_login", "login")
    }
}
