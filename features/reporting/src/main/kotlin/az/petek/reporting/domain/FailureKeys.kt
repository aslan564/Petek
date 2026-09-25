package az.petek.reporting.domain

import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus

/**
 * Recognises the machine-readable failure key an agent or the watchdog writes into a failed step's `detail`
 * (e.g. `mail_timeout: no verification e-mail within 60s`). Reporting must not depend on the agent feature, so its
 * `FailureReason` vocabulary is mirrored in [KNOWN]; a key added there later is still recognised when it leads the
 * detail as `<snake_case_key>:`.
 *
 * It also decides whether a single step record is a failure at all ([isFailure]); [ExpectedOutcomes] adds what needs
 * the whole run (the agent's own records of a lost race), and findings, stability, failed agents and the summary all
 * ask it, so they always agree.
 */
object FailureKeys {
    const val MAIL_TIMEOUT = "mail_timeout"

    /**
     * The test inbox (Mailpit) could not be read at all. Unlike [MAIL_TIMEOUT] this says nothing about the target:
     * it is an environment problem ([environmentProblem]), reported as an agent failure.
     */
    const val MAIL_UNAVAILABLE = "mail_unavailable"

    /**
     * The agent BLOCKs an action with this key when the target refuses it (`report_problem(permission_denied)`).
     * The agent contract calls that the EXPECTED outcome of forbidden-action tests, whose verdict comes from their
     * assertions (`not_visible`, `http_status` 403), so such a step is not a failure (see [isExpectedRefusal]).
     */
    const val PERMISSION_DENIED = "permission_denied"

    /**
     * The orchestrator records an actor that lost a race (`only_one_succeeds`) PASSED with this key: refused as
     * already decided, or it found the object decided by the winner. Like [PERMISSION_DENIED] it is the outcome the
     * step expected; the group assertion decides (see [ExpectedOutcomes], which also covers the agent's own records).
     */
    const val LOST_RACE = "lost_race"

    /** Implied by [StepStatus.BLOCKED] when the watchdog left no key of its own. */
    const val BLOCKED = "blocked"

    val KNOWN: List<String> =
        listOf(
            MAIL_TIMEOUT,
            MAIL_UNAVAILABLE,
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
            LOST_RACE,
        )

    /** Keys caused by the test environment rather than by the target or the agent, with what went wrong. */
    private val ENVIRONMENT_PROBLEMS: Map<String, String> = mapOf(MAIL_UNAVAILABLE to "test inbox unreachable")

    /** Statuses that mean an action did not complete. */
    val FAILING_STATUSES: Set<StepStatus> = setOf(StepStatus.FAILED, StepStatus.ERROR, StepStatus.BLOCKED)

    // A key must stand alone: `timeout` inside `mail_timeout` or `TimeoutError` is not a match.
    private val knownPattern = Regex("(?<![A-Za-z0-9_])(" + KNOWN.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z0-9_])")
    private val leadingKeyPattern = Regex("^\\s*\\[?([a-z][a-z0-9]*(?:_[a-z0-9]+)+)]?\\s*:")

    // The agent loop's last turn of a `do` step: `<observation> | outcome: <STATUS> <failure key>: <summary>`.
    private val outcomeKeyPattern = Regex("\\|\\s*outcome:\\s*[A-Z]+\\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)*)\\s*:")

    /**
     * The failure key of [detail], or null when it carries none. A `key:` prefix wins, because the orchestrator
     * writes `<failure key>: <summary>` and the summary is free text; next the key of an agent loop's
     * `| outcome: <STATUS> <key>:` marker, because the observation before it is free text too (an unreachable inbox
     * may say "Request timeout has expired" and is still `mail_unavailable`); otherwise the first known key counts.
     */
    fun find(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        return leadingKeyPattern.find(detail)?.groupValues?.get(1)
            ?: outcomeKeyPattern.find(detail)?.groupValues?.get(1)
            ?: knownPattern.find(detail)?.groupValues?.get(1)
    }

    /**
     * A BLOCKED step the target refused on purpose ([PERMISSION_DENIED]). The key is looked up in the detail and,
     * when the detail names none, in the action (the agent's own `report_problem` record).
     */
    fun isExpectedRefusal(step: StepRecord): Boolean =
        step.status == StepStatus.BLOCKED && (find(step.detail) ?: find(step.action)) == PERMISSION_DENIED

    /** The orchestrator's record of an action that lost a race: PASSED, detail `lost_race: ...`. */
    fun isLostRace(step: StepRecord): Boolean = step.status == StepStatus.PASSED && find(step.detail) == LOST_RACE

    /**
     * The action did not complete and that was not the expected outcome. Judged from [step] alone: the agent's own
     * records of a lost race need the rest of the run ([ExpectedOutcomes.isFailure]).
     */
    fun isFailure(step: StepRecord): Boolean = step.status in FAILING_STATUSES && !isExpectedRefusal(step)

    /**
     * What went wrong in the test environment when [key] names such a problem (`mail_unavailable` -> "test inbox
     * unreachable"), or null for keys that are about the target or the agent. The report must not read those as bugs.
     */
    fun environmentProblem(key: String): String? = ENVIRONMENT_PROBLEMS[key]

    /** The failure key of a failed step; null for completed steps, expected refusals and failures without a key. */
    fun of(step: StepRecord): String? {
        if (!isFailure(step)) return null
        return find(step.detail) ?: BLOCKED.takeIf { step.status == StepStatus.BLOCKED }
    }
}
