package az.petek.agent.application.runs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Waiting and retry budget of the deterministic run functions. Defaults follow docs/PLAN.md: mail polled every
 * second for at most 60 s, the company code awaited up to 5 minutes, registration tried at most 3 times.
 */
data class RunFunctionSettings(
    /** Waiting for an element that should appear on its own (a form, the user name after login). */
    val uiTimeout: Duration = 15.seconds,
    /** Waiting for the page to move on after a form was submitted; a page that stays means "rejected". */
    val transitionTimeout: Duration = 15.seconds,
    val pollInterval: Duration = 250.milliseconds,
    val mailTimeout: Duration = 60.seconds,
    val mailPollInterval: Duration = 1.seconds,
    /** How long a company-code tester waits for the admin to publish the code. */
    val companyCodeTimeout: Duration = 5.minutes,
    /** The test API may lag behind the UI (company just created, OTP just sent): ask this many times. */
    val oracleAttempts: Int = 5,
    val oracleRetryDelay: Duration = 1.seconds,
    /** Attempts of the whole `register_and_login` flow before the agent is marked failed. */
    val registrationAttempts: Int = 3,
    val retryDelay: Duration = 2.seconds,
) {
    init {
        require(oracleAttempts >= 1) { "oracleAttempts must be at least 1, was $oracleAttempts" }
        require(registrationAttempts >= 1) { "registrationAttempts must be at least 1, was $registrationAttempts" }
        require(pollInterval.isPositive()) { "pollInterval must be positive, was $pollInterval" }
    }
}
