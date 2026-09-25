package az.petek.oracle.domain

import az.petek.core.error.PetekException
import kotlinx.serialization.json.JsonElement

/** Raw oracle answer. [body] is null when the response was empty or not JSON. */
data class OracleResponse(
    val status: Int,
    val body: JsonElement?,
    val rawBody: String,
)

data class TestCompany(
    val id: String,
    val name: String,
    val code: String?,
    val isTest: Boolean,
)

data class Invitee(
    val email: String,
    val name: String,
    val role: String,
    val department: String?,
)

data class SeedCompanyRequest(
    val companyId: String,
    val departments: List<String>,
    val invites: List<Invitee>,
)

data class SeedCompanyResult(
    val companyId: String,
    val companyCode: String?,
    val departmentIds: Map<String, String>,
    /** email -> invitation link (if the target returns it; otherwise the link arrives by e-mail). */
    val inviteLinks: Map<String, String>,
)

/**
 * Port: the target's test-only API (`/test/...`, `X-Test-Token`), the source of truth "C" in the A/B/C comparison.
 * Contract: docs/TARGET_CONTRACT.md. Destructive calls only touch companies with `is_test=true` (CLAUDE.md rule 8).
 */
interface TargetOracle {
    /** False when no test token is configured or the target has no test API; oracle assertions are then skipped. */
    val isAvailable: Boolean

    /** Generic GET used by `oracle` assertions (path already rendered, e.g. `/test/tickets/42`). */
    suspend fun get(path: String): OracleResponse

    suspend fun latestOtp(phone: String): String?

    suspend fun companyByOwner(ownerEmail: String): TestCompany?

    suspend fun company(companyId: String): TestCompany?

    suspend fun seedCompany(request: SeedCompanyRequest): SeedCompanyResult

    /** Refuses (throws [OracleSafetyException]) unless the company exists and is flagged `is_test`. */
    suspend fun deleteCompany(companyId: String)
}

/** Selects a value by dotted path with optional indexes: `status`, `assignee.email`, `history[0].to`. Pure. */
interface JsonFieldSelector {
    fun select(
        root: JsonElement,
        path: String,
    ): JsonElement?
}

class OracleSafetyException(
    message: String,
) : PetekException(message)

class OracleException(
    message: String,
    cause: Throwable? = null,
) : PetekException(message, cause)
