package az.petek.agent.domain

import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.TemplateContext
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.RunId
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
 * `invite_link:<email>`. Readers can suspend until a value appears.
 */
interface SharedRunState {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )

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

/** Everything one agent owns during a run. The identity is read-only (CLAUDE.md rule 7). */
data class AgentRuntime(
    val runId: RunId,
    val identity: Identity,
    /** All identities of the run, read-only (the admin needs it to invite the others). */
    val roster: List<Identity>,
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

/** Why an action failed, as shown in the report (`mail_timeout`, `otp_rejected`, `blocked`, ...). */
enum class FailureReason(
    val key: String,
) {
    MAIL_TIMEOUT("mail_timeout"),
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
