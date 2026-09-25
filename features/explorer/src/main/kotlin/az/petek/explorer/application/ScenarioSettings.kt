package az.petek.explorer.application

import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.RoleQuota
import az.petek.explorer.domain.ExplorationId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Names of the deterministic `run` functions a generated setup uses (the agent module's built-ins by default). */
data class SetupFunctions(
    val registerOwner: String = "register_owner",
    val seedCompany: String = "seed_company",
    val join: String = "register_and_login",
)

/**
 * The fixed frame of a generated campaign: a small team that can express every covered idea (two managers for races,
 * several employees for real-time fan-out), its departments, and the limits of the assertions it writes.
 */
data class ScenarioSettings(
    val team: RoleQuota = RoleQuota(admin = 1, manager = 2, employee = 3),
    val departments: List<String> = listOf("IT", "HR"),
    val seed: Long = 42,
    val budget: Budget = Budget(maxStepsPerAgent = 40, maxMinutes = 20),
    val setup: SetupFunctions = SetupFunctions(),
    val visibleWithin: Duration = 10.seconds,
    val waitTimeout: Duration = 30.seconds,
    val maxLatency: Duration = 5_000.milliseconds,
    val forbiddenStatus: Int = 403,
) {
    init {
        require(team.admin == 1) { "a generated campaign has exactly one admin, who owns the company" }
        require(team.manager >= 0 && team.employee >= 0) { "role counts must not be negative" }
        require(departments.isNotEmpty()) { "a generated campaign needs at least one department" }
    }
}

/** What to generate a draft from: the model of [explorationId], grounded by [instructions] (default: the exploration's). */
data class ScenarioRequest(
    val explorationId: ExplorationId,
    val instructions: String? = null,
    val maxIdeas: Int = 8,
    /** The target implements the `/test/...` API of docs/TARGET_CONTRACT.md: use it for ids and oracle checks. */
    val testApi: Boolean = false,
    val name: String? = null,
) {
    init {
        require(maxIdeas in 1..MAX_IDEAS) { "maxIdeas must be in 1..$MAX_IDEAS, was $maxIdeas" }
        require(name == null || name.isNotBlank()) { "a draft name must not be blank" }
    }

    companion object {
        const val MAX_IDEAS = 50
    }
}
