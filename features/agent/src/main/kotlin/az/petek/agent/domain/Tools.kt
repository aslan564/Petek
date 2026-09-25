package az.petek.agent.domain

import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * The complete whitelist of what an LLM agent may do (CLAUDE.md rule 3). A new capability is a new subtype
 * here plus its executor — never a prompt change. `text` may contain harness placeholders such as
 * `{self.password}` or `{vars.email_code}`; the harness substitutes them, so secrets never reach the LLM.
 */
sealed interface AgentAction {
    val toolName: String

    data class Navigate(
        val url: String,
    ) : AgentAction {
        override val toolName = "navigate"
    }

    data class Click(
        val ref: Int,
    ) : AgentAction {
        override val toolName = "click"
    }

    data class Type(
        val ref: Int,
        val text: String,
        val submit: Boolean,
    ) : AgentAction {
        override val toolName = "type"
    }

    data class Select(
        val ref: Int,
        val option: String,
    ) : AgentAction {
        override val toolName = "select"
    }

    data class ReadText(
        val selector: String,
    ) : AgentAction {
        override val toolName = "read_text"
    }

    data class WaitText(
        val text: String,
        val timeout: Duration,
    ) : AgentAction {
        override val toolName = "wait_text"
    }

    /** Harness reads the newest verification e-mail for this agent and stores it as `{vars.email_code}`. */
    data object GetEmailCode : AgentAction {
        override val toolName = "get_email_code"
    }

    /**
     * Harness reads the newest phone code (OTP) sent to this agent's phone from the target's test API
     * (`GET /test/otp/{phone}`, docs/TARGET_CONTRACT.md) and stores it as `{vars.phone_code}`. The test mode sends no
     * SMS, so this is the only way a `do` step can pass the site's phone-verification step.
     */
    data object GetPhoneCode : AgentAction {
        override val toolName = "get_phone_code"
    }

    data class Done(
        val summary: String,
        val success: Boolean,
        val objectId: String?,
    ) : AgentAction {
        override val toolName = "done"
    }

    data class ReportProblem(
        val kind: ProblemKind,
        val note: String,
    ) : AgentAction {
        override val toolName = "report_problem"
    }
}

enum class ProblemKind(
    val key: String,
) {
    BUG("bug"),
    BLOCKED("blocked"),
    PERMISSION_DENIED("permission_denied"),
    UNEXPECTED_UI("unexpected_ui"),
    OTHER("other"),
    ;

    companion object {
        fun fromKey(key: String): ProblemKind = entries.firstOrNull { it.key == key.trim().lowercase() } ?: OTHER
    }
}

data class AgentDecision(
    val reason: String,
    val action: AgentAction,
)

sealed interface DecisionParse {
    data class Valid(
        val decision: AgentDecision,
    ) : DecisionParse

    /** The model produced something outside the whitelist or with missing arguments. */
    data class Invalid(
        val error: String,
    ) : DecisionParse
}

/**
 * The single JSON shape the model must answer with, and its strict parser.
 * Shape: `{"reason": str, "tool": enum, ...tool args}`; unknown tools or missing args -> [DecisionParse.Invalid].
 */
interface DecisionProtocol {
    val toolNames: List<String>

    fun responseSchema(): JsonObject

    /** Human-readable tool reference for the system prompt. */
    fun describeTools(): String

    fun parse(output: JsonObject): DecisionParse
}

/** Detects the agent going in circles: the same action [threshold] times in a row. */
interface LoopDetector {
    /** Returns true when [action] completes a loop. */
    fun register(action: AgentAction): Boolean

    fun reset()
}
