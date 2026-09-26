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

package az.petek.campaign.domain

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import java.net.URI
import kotlin.time.Duration

/** A validated campaign: who tests, against what, and which scenario steps they run. */
data class Campaign(
    val settings: CampaignSettings,
    val target: TargetProfile,
    val setup: List<ScenarioStep>,
    val steps: List<ScenarioStep>,
    /** SHA-256 of the source file; identifies the campaign version in run records. */
    val sourceHash: String,
    /** Where settings, assertions and id sources were declared, for validation messages. Empty for code-built campaigns. */
    val sourceLines: SourceLines = SourceLines.NONE,
) {
    val allSteps: List<ScenarioStep> get() = setup + steps
}

data class CampaignSettings(
    val target: URI,
    val testers: Int,
    val seed: Long,
    /** Names given by the user; used first, then the built-in name catalog. */
    val names: List<String>,
    val roles: RoleQuota,
    val departments: List<String>,
    val registration: RegistrationQuota,
    val budget: Budget,
    val onFail: OnFail,
    /** Campaign name from the YAML (`campaign.name`), default = file name without extension. */
    val name: String = "campaign",
    /** How the actors of a step are started (`campaign.pacing`); by default all at once. */
    val pacing: Pacing = Pacing.NONE,
    /** Whether the site has companies (`campaign.tenant`); portal-shaped campaigns keep the default. */
    val tenant: Tenant = Tenant.COMPANY,
    /**
     * `campaign.wave_size` (Faza 21): at most this many testers are live at once. The testers are split in agent order
     * into waves of this size; each wave opens its browsers, runs every step with its own testers only (an event one
     * tester emits reaches only its own wave) and closes them before the next wave starts. Null: everyone at once.
     */
    val waveSize: Int? = null,
)

/**
 * How the actors of one step start their action, so that a real site's per-IP rate limits (e.g. 50 sign-ins a
 * minute) are not hit by every tester signing up at the same instant: actor *n* (in agent id order, from 0) starts no
 * earlier than [startStagger] × *n* after the step began, and at most [maxParallelActors] act at once (null: no
 * limit). Steps with `parallel: true` ignore both: a race needs its actors to start together. Steps without an action
 * (only assertions) are not paced either: they send nothing to the target.
 */
data class Pacing(
    val startStagger: Duration = Duration.ZERO,
    val maxParallelActors: Int? = null,
) {
    /** Whether this pacing changes anything at all. */
    val isNone: Boolean get() = startStagger == Duration.ZERO && maxParallelActors == null

    companion object {
        val NONE: Pacing = Pacing()
    }
}

/**
 * How many testers play each role. [admin], [manager] and [employee] are the roles of a site with companies
 * (`tenant: company`); [others] holds the roles a campaign names itself (`editor: 2`), which a site without companies
 * uses instead.
 */
data class RoleQuota(
    val admin: Int,
    val manager: Int,
    val employee: Int,
    val others: Map<Role, Int> = emptyMap(),
) {
    val total: Int get() = admin + manager + employee + others.values.sum()

    /** Every role with its count, company roles first (also when zero), then [others] in their order. */
    val counts: Map<Role, Int>
        get() = linkedMapOf(Role.ADMIN to admin, Role.MANAGER to manager, Role.EMPLOYEE to employee) + others

    /** Roles with at least one tester. */
    val roles: List<Role> get() = counts.filterValues { it > 0 }.keys.toList()

    fun count(role: Role): Int =
        when (role) {
            Role.ADMIN -> admin
            Role.MANAGER -> manager
            Role.EMPLOYEE -> employee
            else -> others[role] ?: 0
        }

    companion object {
        /** A quota of campaign-named roles only, for a site without companies. */
        fun of(counts: Map<Role, Int>): RoleQuota =
            RoleQuota(
                admin = counts[Role.ADMIN] ?: 0,
                manager = counts[Role.MANAGER] ?: 0,
                employee = counts[Role.EMPLOYEE] ?: 0,
                others = counts.filterKeys { !it.isCompanyRole },
            )
    }
}

/**
 * Whether the site organises its users in companies (e.g. an admin creates one, the others join it) or not (a
 * shop, a blog, a plain sign-in application). Decides the setup steps, the registration modes, the prompt's company
 * context and what the teardown removes.
 */
enum class Tenant(
    val key: String,
) {
    COMPANY("company"),
    NONE("none"),
    ;

    companion object {
        fun fromKey(key: String): Tenant? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * How testers get onto the site. With companies ([Tenant.COMPANY]) the non-admins join the admin's company:
 * `invite + companyCode` must equal managers + employees, and `invite` must be at least the number of managers: the
 * target's company-code form has no role field, so whoever joins with the code becomes an employee, and managers
 * therefore always join by invitation. The remaining invitations and every company code go to employees. The YAML may
 * omit it; the loader then splits evenly (invite gets the extra one), but never invites fewer testers than there are
 * managers.
 *
 * Without companies ([Tenant.NONE]) every tester passes its own gate: [self] sign up on their own, [login] sign in with
 * an account the owner gave for their role, [guest] stay visitors; they add up to the testers.
 */
data class RegistrationQuota(
    val invite: Int,
    val companyCode: Int,
    val self: Int = 0,
    val login: Int = 0,
    val guest: Int = 0,
) {
    fun count(mode: RegistrationMode): Int =
        when (mode) {
            RegistrationMode.INVITE -> invite
            RegistrationMode.COMPANY_CODE -> companyCode
            RegistrationMode.SELF -> self
            RegistrationMode.LOGIN -> login
            RegistrationMode.GUEST -> guest
            else -> 0
        }

    /** Testers passing a gate of a site without companies. */
    val gates: Int get() = self + login + guest

    companion object {
        /** Every one of [testers] signs up on their own: the default of a site without companies. */
        fun selfSignUp(testers: Int): RegistrationQuota = RegistrationQuota(0, 0, self = testers)
    }
}

data class Budget(
    val maxStepsPerAgent: Int,
    val maxMinutes: Int,
)

enum class OnFail { CONTINUE, ABORT }

enum class StepPhase { SETUP, MAIN }

data class ScenarioStep(
    /** Unique within the campaign. Generated as `setup-1`, `step-3`, ... when the YAML omits it. */
    val id: String,
    val phase: StepPhase,
    val actors: ActorExpression,
    val action: StepAction,
    val emits: EmitSpec?,
    val waitFor: WaitForSpec?,
    val parallel: Boolean,
    val assertions: List<AssertionSpec>,
    val onFail: OnFail?,
    /** 1-based line in the campaign file, for error messages and reports. */
    val line: Int,
)

/**
 * The step tests that an action is forbidden: its assertions check the refusal itself (`not_visible` of the control,
 * `http_status` 401/403 of the request). An agent that could not perform such an action did what the step asked, so
 * the orchestrator lets those assertions decide (AGENTS.md rule 2) instead of the wording of the agent's report.
 */
val ScenarioStep.expectsRefusal: Boolean get() = assertions.any(AssertionSpec::expectsRefusal)

/** `not_visible`, or `http_status` expecting 401/403: the assertion itself checks that the target refuses. */
val AssertionSpec.expectsRefusal: Boolean
    get() =
        when (this) {
            is AssertionSpec.NotVisible -> true
            is AssertionSpec.HttpStatus -> equals in REFUSAL_STATUSES
            else -> false
        }

/** HTTP statuses that mean "refused": unauthenticated and forbidden. */
private val REFUSAL_STATUSES = setOf(401, 403)

sealed interface StepAction {
    /** Natural-language task interpreted by the LLM agent through whitelisted tools only. */
    data class Do(
        val instruction: String,
    ) : StepAction

    /** Deterministic function executed by code (no LLM). */
    data class Run(
        val function: String,
        val args: Map<String, String> = emptyMap(),
    ) : StepAction

    /** A step that only waits and/or asserts. */
    data object None : StepAction
}

/** `emits: announcement_created` or the long form with an explicit id source. */
data class EmitSpec(
    val event: String,
    val idSource: IdSource?,
)

/** Where the harness reads the id of the object a step created (so the LLM is not the source of truth). */
sealed interface IdSource {
    /** First capture group of the regex applied to the page URL after the step. */
    data class UrlRegex(
        val regex: String,
    ) : IdSource

    /** Field of an oracle response, e.g. `/test/announcements/latest?by={self.email}` + `id`. */
    data class OracleField(
        val path: String,
        val field: String,
    ) : IdSource

    /** Attribute of an element on the page, e.g. `[data-testid=announcement-item]:first-child` + `data-id`. */
    data class DomAttribute(
        val selector: String,
        val attribute: String,
    ) : IdSource

    /** Fallback: the `object_id` the agent reported with `done`. Marked as agent-reported in evidence. */
    data object AgentReport : IdSource
}

data class WaitForSpec(
    val event: String,
    val timeout: Duration,
)

/** Typed checks executed by code (AGENTS.md rule 2). */
sealed interface AssertionSpec {
    val type: String

    data class VisibleText(
        val text: String,
        val within: Duration,
    ) : AssertionSpec {
        override val type = "visible_text"
    }

    data class NotVisible(
        val text: String?,
        val selector: String?,
    ) : AssertionSpec {
        override val type = "not_visible"
    }

    data class Oracle(
        val path: String,
        val field: String?,
        val equals: String?,
        val contains: String?,
    ) : AssertionSpec {
        override val type = "oracle"
    }

    data class HttpStatus(
        val path: String,
        val method: String,
        val equals: Int,
    ) : AssertionSpec {
        override val type = "http_status"
    }

    data class Count(
        val selector: String,
        val equals: Int,
    ) : AssertionSpec {
        override val type = "count"
    }

    data class LatencyMax(
        val max: Duration,
    ) : AssertionSpec {
        override val type = "latency_max"
    }

    /**
     * Exactly one actor of a `parallel` step wins, judged by code from each actor's own requests (AGENTS.md rule 2),
     * never from what the agent says: an actor won when one of its requests matching [request] was accepted
     * (status < 400) and none was refused (403, 409, 422). YAML `only_one_succeeds: true` checks every mutating
     * request ([request] null); the map form `{request: "<METHOD> <path regex>", oracle: {path, field, equals}}`
     * narrows the requests and adds a check of the target's final state through its test API ([oracle]).
     */
    data class OnlyOneSucceeds(
        val request: RequestPattern? = null,
        val oracle: OracleCondition? = null,
    ) : AssertionSpec {
        override val type = "only_one_succeeds"

        /** The requests that decide: [request], or any mutating request to the target. */
        val effectiveRequest: RequestPattern get() = request ?: RequestPattern.ANY_MUTATION
    }
}

/**
 * What the target's test API must say after a race, e.g. `{path: "/test/tickets/{last_id}", field: status, equals:
 * approved}`: `GET` [path] answers 2xx and, with [field], that field exists; with [equals], the field (or the whole
 * body without [field]) equals it. Same rules as the `oracle` assertion.
 */
data class OracleCondition(
    val path: String,
    val field: String? = null,
    val equals: String? = null,
)
