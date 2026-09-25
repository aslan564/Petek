package az.petek.reporting.domain

import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus

/**
 * Recognises the machine-readable failure key an agent or the watchdog writes into a failed step's `detail`
 * (e.g. `mail_timeout: no verification e-mail within 60s`). Reporting must not depend on the agent feature, so its
 * `FailureReason` vocabulary is mirrored in [KNOWN]; a key added there later is still recognised when it leads the
 * detail as `<snake_case_key>:`.
 *
 * It is also the single place that decides whether a step record is a failure at all, so findings, stability,
 * failed agents and the summary always agree (see [isFailure]).
 */
object FailureKeys {
    const val MAIL_TIMEOUT = "mail_timeout"

    /**
     * The agent BLOCKs an action with this key when the target refuses it (`report_problem(permission_denied)`).
     * The agent contract calls that the EXPECTED outcome of forbidden-action tests, whose verdict comes from their
     * assertions (`not_visible`, `http_status` 403), so such a step is not a failure (see [isExpectedRefusal]).
     */
    const val PERMISSION_DENIED = "permission_denied"

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
            PERMISSION_DENIED,
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

    /**
     * The failure key of [detail], or null when it carries none. A `key:` prefix wins, because the orchestrator
     * writes `<failure key>: <summary>` and the summary is free text; otherwise the first known key counts.
     */
    fun find(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        return leadingKeyPattern.find(detail)?.groupValues?.get(1)
            ?: knownPattern.find(detail)?.groupValues?.get(1)
    }

    /**
     * A BLOCKED step the target refused on purpose ([PERMISSION_DENIED]). The key is looked up in the detail and,
     * when the detail names none, in the action (the agent's own `report_problem` record).
     */
    fun isExpectedRefusal(step: StepRecord): Boolean =
        step.status == StepStatus.BLOCKED && (find(step.detail) ?: find(step.action)) == PERMISSION_DENIED

    /** The action did not complete and that was not the expected outcome. */
    fun isFailure(step: StepRecord): Boolean = step.status in FAILING_STATUSES && !isExpectedRefusal(step)

    /** The failure key of a failed step; null for completed steps, expected refusals and failures without a key. */
    fun of(step: StepRecord): String? {
        if (!isFailure(step)) return null
        return find(step.detail) ?: BLOCKED.takeIf { step.status == StepStatus.BLOCKED }
    }
}
