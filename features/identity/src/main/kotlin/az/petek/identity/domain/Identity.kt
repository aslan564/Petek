package az.petek.identity.domain

import az.petek.core.error.PetekException
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret

/**
 * One tester's identity. Created only by the orchestrator (CLAUDE.md rule 7); agents read it and never invent one.
 * Example: a07, "Əli Kərimov", eli.k7x2.a07@test.kadrohr.com.
 */
data class Identity(
    val agentId: AgentId,
    val displayName: String,
    val email: String,
    val password: Secret,
    /** Fake number `+99450XXXXXXX`; never a real subscriber. */
    val phone: String,
    val role: Role,
    /** Null for the admin. */
    val department: String?,
    val registration: RegistrationMode,
    val status: IdentityStatus = IdentityStatus.PLANNED,
    val storageStatePath: String? = null,
)

enum class IdentityStatus { PLANNED, REGISTERED, ACTIVE, FAILED }

/** Input for the registry generator, mapped from the campaign by the orchestrator. */
data class IdentitySpec(
    val testers: Int,
    val seed: Long,
    val names: List<String>,
    val admins: Int,
    val managers: Int,
    val employees: Int,
    val departments: List<String>,
    /** Testers who join by invitation: every manager plus some employees, so never fewer than [managers]. */
    val inviteCount: Int,
    /** Testers who join with the company code; always employees. */
    val companyCodeCount: Int,
    /** Catch-all test domain, e.g. `test.kadrohr.com`. */
    val mailDomain: String,
)

data class IdentityPlan(
    val runTag: RunTag,
    val identities: List<Identity>,
)

/** A registry that cannot be built (duplicate names, impossible quotas). The run must not start. */
class IdentityConflictException(
    message: String,
) : PetekException(message)

/**
 * Builds the registry deterministically: the same spec + run tag + secret always yields the same identities.
 * Rules (docs/PLAN.md "Kimlik reyestri"): admin is a01; one manager per department (round-robin if counts differ);
 * employees spread round-robin over departments; given names first, then the catalog; display names and e-mails
 * unique. Managers always join by invitation (a company-code sign-up becomes an employee on the target), the other
 * invitations go to employees with a seeded, department-stratified shuffle, so each department gets a mix of invite
 * and company code, and company-code identities are employees only.
 */
interface IdentityRegistryGenerator {
    fun generate(
        spec: IdentitySpec,
        runTag: RunTag,
    ): IdentityPlan
}

/** Derives a strong but reproducible password per identity (HMAC over run tag + agent id with a local secret). */
fun interface PasswordDeriver {
    fun derive(
        runTag: RunTag,
        agentId: AgentId,
    ): Secret
}

/** Azerbaijani first names and surnames used after the user's names are exhausted. */
interface NameCatalog {
    val firstNames: List<String>
    val surnames: List<String>

    /**
     * The form of [surname] that goes with [firstName]. Azerbaijani surnames agree with gender
     * (Günel Məmmədova, Əli Məmmədov); catalogs without such a rule keep the default, which returns [surname] as is.
     * Must be injective per first name, so distinct surnames stay distinct display names.
     */
    fun surnameFor(
        firstName: String,
        surname: String,
    ): String = surname
}

/** Port: identities persisted per run (UNIQUE(email), UNIQUE(run_id, display_name)). */
interface IdentityRepository {
    /** Replaces any identities already stored for [runId] in one transaction (so `petek plan` is repeatable). */
    suspend fun replaceAll(
        runId: RunId,
        plan: IdentityPlan,
    )

    suspend fun findByRun(runId: RunId): List<Identity>

    suspend fun updateStatus(
        runId: RunId,
        agentId: AgentId,
        status: IdentityStatus,
        reason: String? = null,
    )

    suspend fun updateStorageState(
        runId: RunId,
        agentId: AgentId,
        path: String,
    )
}
