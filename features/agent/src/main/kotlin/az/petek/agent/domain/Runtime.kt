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

package az.petek.agent.domain

import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.TemplateContext
import az.petek.core.ids.AgentId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.RunId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.identity.domain.Identity
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Per-agent scratch values (e.g. `email_code`, `last_object_id`). Only the harness writes them. */
class AgentVariables {
    private val values = ConcurrentHashMap<String, String>()

    operator fun get(key: String): String? = values[key]

    operator fun set(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    fun snapshot(): Map<String, String> = values.toMap()
}

/**
 * Values shared by all agents of one run and produced during setup: `company_id`, `company_code`,
 * `invite_link:<email>`. Readers can suspend until a value appears. Values are write-once: the first publisher of a
 * key wins, and a later [put] of a different value is refused (returns false) so no agent can change what the others
 * already act on; publishing the same value again is fine.
 */
interface SharedRunState {
    fun get(key: String): String?

    /** Stores [value] under [key] unless the key already holds a different value; returns whether [key] now holds [value]. */
    fun put(
        key: String,
        value: String,
    ): Boolean

    suspend fun await(
        key: String,
        timeout: Duration,
    ): String?

    companion object Keys {
        const val COMPANY_ID = "company_id"
        const val COMPANY_CODE = "company_code"

        fun inviteLink(email: String) = "invite_link:${email.lowercase()}"
    }
}

/**
 * What one agent may know about another tester of the run: who they are and how they join, never how they sign in.
 * There is no password and no phone here, so a colleague's credentials cannot reach another agent's runtime, prompt
 * or evidence by construction (least privilege; AGENTS.md rules 7 and 10).
 */
data class Colleague(
    val agentId: AgentId,
    val displayName: String,
    val email: String,
    val role: Role,
    /** Null for the admin. */
    val department: String?,
    val registration: RegistrationMode,
) {
    companion object {
        fun of(identity: Identity): Colleague =
            Colleague(
                agentId = identity.agentId,
                displayName = identity.displayName,
                email = identity.email,
                role = identity.role,
                department = identity.department,
                registration = identity.registration,
            )
    }
}

/** Everything one agent owns during a run. The identity is read-only (AGENTS.md rule 7). */
data class AgentRuntime(
    val runId: RunId,
    val identity: Identity,
    /** Everyone of the run as [Colleague]s (the admin invites them, agents address them by name); never their secrets. */
    val roster: List<Colleague>,
    val session: BrowserSession,
    val target: TargetProfile,
    val variables: AgentVariables,
    val shared: SharedRunState,
    val runStartedAt: Instant,
    /** Where this agent's storage state is saved after login. */
    val storageStatePath: Path,
)

/** The scenario step an agent is working on. */
data class StepContext(
    val scenarioStep: String,
    val correlationId: CorrelationId,
    val templates: TemplateContext,
    /** Upper bound of LLM decisions for a `do` step (campaign budget). */
    val maxSteps: Int,
    val timeout: Duration,
)

enum class ActionStatus { SUCCEEDED, FAILED, BLOCKED, ERROR }

/**
 * Why an action failed, as shown in the report (`mail_timeout`, `otp_rejected`, `blocked`, ...). Most reasons are
 * findings about the target or the agent; [MAIL_UNAVAILABLE] and [LLM_UNAVAILABLE] are environment problems (the
 * test inbox or the model could not be reached), reported with [ActionStatus.ERROR].
 */
enum class FailureReason(
    val key: String,
) {
    /** The inbox was reachable, but no usable verification e-mail arrived in time: the target sent nothing. */
    MAIL_TIMEOUT("mail_timeout"),

    /** The test inbox itself could not be read (unreachable, timing out, answering garbage) for the whole wait. */
    MAIL_UNAVAILABLE("mail_unavailable"),
    OTP_REJECTED("otp_rejected"),
    REGISTRATION_FAILED("registration_failed"),
    LOGIN_FAILED("login_failed"),
    IDENTITY_MISMATCH("identity_mismatch"),
    LOOP_DETECTED("loop_detected"),
    STEP_LIMIT("step_limit"),
    TIMEOUT("timeout"),
    PROBLEM_REPORTED("problem_reported"),
    PERMISSION_DENIED("permission_denied"),
    INVALID_DECISION("invalid_decision"),
    LLM_UNAVAILABLE("llm_unavailable"),
    BROWSER_ERROR("browser_error"),
    MISSING_PREREQUISITE("missing_prerequisite"),

    /** `site_health` found broken links, console or network errors, slow requests, a bad back button or layout. */
    UNHEALTHY_PAGE("unhealthy_page"),

    /**
     * The site answered `429 Too Many Requests` during the action: every tester came from one IP address. A gap of the
     * test set-up, not a bug of the site (Faza 21); `PETEK_PROXIES` gives each tester its own address.
     */
    RATE_LIMITED("rate_limited"),

    /** `direct_url`: a page of someone else's object opened for a tester who must not see it. */
    ACCESS_NOT_REFUSED("access_not_refused"),
}

data class ActionOutcome(
    val status: ActionStatus,
    val summary: String,
    /** Id of an object the action created, when known (used for `emits`). */
    val objectId: String? = null,
    val failureReason: FailureReason? = null,
    /** Number of LLM decisions (do) or sub-actions (run) performed. */
    val stepsTaken: Int = 0,
) {
    val succeeded: Boolean get() = status == ActionStatus.SUCCEEDED
}
