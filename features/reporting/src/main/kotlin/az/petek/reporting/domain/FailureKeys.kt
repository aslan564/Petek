package az.petek.reporting.domain

import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus

/**
 * Recognises the machine-readable failure key an agent or the watchdog writes into a failed step's `detail`
 * (e.g. `mail_timeout: no verification e-mail within 60s`). Reporting must not depend on the agent feature, so its
 * `FailureReason` vocabulary is mirrored in [KNOWN]; a key added there later is still recognised when it leads the
 * detail as `<snake_case_key>:`.
 */
object FailureKeys {
    const val MAIL_TIMEOUT = "mail_timeout"

    /** Implied by [StepStatus.BLOCKED] when the watchdog left no key of its own. */
    const val BLOCKED = "blocked"

    val KNOWN: List<String> =
        listOf(
            MAIL_TIMEOUT,
            "otp_rejected",
            "registration_failed",
            "login_failed",
            "identity_mismatch",
            "loop_detected",
            "step_limit",
            "timeout",
            "problem_reported",
            "permission_denied",
            "invalid_decision",
            "llm_unavailable",
            "browser_error",
            "missing_prerequisite",
            BLOCKED,
        )

    /** Statuses that mean an action did not complete. */
    val FAILING_STATUSES: Set<StepStatus> = setOf(StepStatus.FAILED, StepStatus.ERROR, StepStatus.BLOCKED)

    // A key must stand alone: `timeout` inside `mail_timeout` or `TimeoutError` is not a match.
    private val knownPattern = Regex("(?<![A-Za-z0-9_])(" + KNOWN.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z0-9_])")
    private val leadingKeyPattern = Regex("^\\s*\\[?([a-z][a-z0-9]*(?:_[a-z0-9]+)+)]?\\s*:")

    /** The first failure key in [detail], or null when it carries none. */
    fun find(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        return knownPattern.find(detail)?.groupValues?.get(1)
            ?: leadingKeyPattern.find(detail)?.groupValues?.get(1)
    }

    /** The failure key of a step that did not complete; null for completed steps and failures without a key. */
    fun of(step: StepRecord): String? {
        if (step.status !in FAILING_STATUSES) return null
        return find(step.detail) ?: BLOCKED.takeIf { step.status == StepStatus.BLOCKED }
    }
}
