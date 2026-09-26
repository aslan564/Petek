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

package az.petek.identity.domain

import az.petek.core.error.PetekException
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.WorkspaceId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret

/**
 * One tester's identity. Created only by the orchestrator (AGENTS.md rule 7); agents read it and never invent one.
 * Example: a07, "Əli Kərimov", eli.k7x2.a07@test.portal.example.
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
    /** Catch-all test domain, e.g. `test.portal.example`. */
    val mailDomain: String,
    /**
     * The owner's own inbox, e.g. `test@company.az` (Faza 16): when set, every tester gets its `+` address,
     * `test+<runTag>-<agentId>@company.az`, instead of an address of [mailDomain]; mail is routed back to the tester by
     * that exact address.
     */
    val mailbox: String? = null,
    /**
     * False for a site without companies (`tenant: none`): [ownRoles] and [gates] then replace the admin, manager and
     * employee counts and the invite and company-code quotas, and nobody is an admin by rule.
     */
    val companies: Boolean = true,
    /** Testers per campaign role on a site without companies, in roster order. */
    val ownRoles: Map<Role, Int> = emptyMap(),
    /** Testers per gate ([RegistrationMode.GATES]) on a site without companies. */
    val gates: Map<RegistrationMode, Int> = emptyMap(),
    /** The owner's accounts; each [RegistrationMode.LOGIN] tester signs in with one of its role (Faza 18). */
    val accounts: List<GivenAccount> = emptyList(),
)

/** An account the owner gave for [role]; a `login` tester uses it instead of signing up. */
data class GivenAccount(
    val role: Role,
    val email: String,
    val password: Secret,
    /** The name the site shows for it, when the owner gave one; the identity check compares against it. */
    val displayName: String? = null,
) {
    override fun toString(): String = "GivenAccount(role=$role, email=$email, password=***)"
}

data class IdentityPlan(
    val runTag: RunTag,
    val identities: List<Identity>,
    /** Always [WorkspaceId.LOCAL] on the owner's machine (ADR-0011). */
    val workspaceId: WorkspaceId = WorkspaceId.LOCAL,
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

/**
 * Azerbaijani first names and surnames used after the user's names are exhausted. The registry never runs out of
 * names: once every first name × surname pair is used it adds a patronymic ([patronymic], from [fatherNames]), and
 * once those are used too, an ordinal (`Əli Məmmədov II`).
 */
interface NameCatalog {
    val firstNames: List<String>
    val surnames: List<String>

    /** Given names that serve as fathers' names in a [patronymic]; by default every first name. */
    val fatherNames: List<String> get() = firstNames

    /**
     * The form of [surname] that goes with [firstName]. Azerbaijani surnames agree with gender
     * (Günel Məmmədova, Əli Məmmədov); catalogs without such a rule keep the default, which returns [surname] as is.
     * Must be injective per first name, so distinct surnames stay distinct display names.
     */
    fun surnameFor(
        firstName: String,
        surname: String,
    ): String = surname

    /**
     * The part between first name and surname that names the father of a person called [firstName]: Azerbaijani
     * `Vüqar oğlu` / `Vüqar qızı`. Catalogs without such a rule keep the default, the father's name as a middle name.
     * Must be injective per first name, so distinct fathers stay distinct display names.
     */
    fun patronymic(
        firstName: String,
        fatherName: String,
    ): String = fatherName
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
