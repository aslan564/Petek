package az.petek.agent.application.runs

import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.FailureReason
import az.petek.evidence.domain.StepStatus
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer

/**
 * The sign-in journey shared by the run functions (docs/TARGET_CONTRACT.md §2): log in, confirm the e-mail code
 * (one retry with a newer code, then `otp_rejected`), confirm the phone code from the test API when the site asks
 * for it, and prove whose session the browser holds. A code counts as rejected when its form is still shown after
 * [RunFunctionSettings.transitionTimeout].
 */
internal class TargetFlows(
    private val oracle: TargetOracle,
    private val verification: AwaitVerificationUseCase,
    private val settings: RunFunctionSettings,
) {
    /** Submits the login form and requires the signed-in user name; the site's error text goes into the failure. */
    suspend fun login(trace: RunTrace) {
        submitLogin(trace)
        if (!trace.waitFor(USER_NAME, settings.uiTimeout)) {
            val why = loginError(trace) ?: "no signed-in user after ${settings.uiTimeout}"
            throw RunFailure(FailureReason.LOGIN_FAILED, "Login as ${trace.runtime.identity.email} failed: $why")
        }
    }

    /**
     * Drives the journey from [start] until the user is signed in. Each step is taken at most once: a site that
     * asks for the same step again is reported instead of looping.
     */
    suspend fun signIn(
        trace: RunTrace,
        start: PageState,
    ) {
        val done = mutableSetOf<PageState>()
        var state = start
        while (state != PageState.LOGGED_IN) {
            if (!done.add(state)) {
                val reason = if (state == PageState.LOGIN_PAGE) FailureReason.LOGIN_FAILED else FailureReason.REGISTRATION_FAILED
                throw RunFailure(reason, "The site asked for ${state.label} again (${trace.currentUrl()}).")
            }
            state =
                when (state) {
                    PageState.EMAIL_CODE -> {
                        confirmEmailCode(trace)
                    }

                    PageState.PHONE_CODE -> {
                        confirmPhoneCode(trace)
                    }

                    PageState.LOGIN_PAGE -> {
                        loginFromLoginPage(trace)
                    }

                    PageState.OTHER -> {
                        throw RunFailure(FailureReason.REGISTRATION_FAILED, "Unexpected page while signing in: ${trace.currentUrl()}.")
                    }

                    PageState.LOGGED_IN -> {
                        state
                    }
                }
        }
    }

    /**
     * Proves the session belongs to this agent: the page's current user name equals the identity's display name
     * (trimmed, whitespace collapsed, Unicode NFC). This is the session-isolation check of docs/PLAN.md.
     */
    suspend fun verifyIdentity(trace: RunTrace): String {
        val expected = normalizeName(trace.runtime.identity.displayName)
        if (!trace.waitFor(USER_NAME, settings.uiTimeout)) {
            throw RunFailure(FailureReason.LOGIN_FAILED, "Not signed in: no current user name on ${trace.currentUrl()}.")
        }
        val shown = trace.readText(USER_NAME)?.let(::normalizeName).orEmpty()
        val same = trace.probe("current user is '$expected'", { shown == expected }) { it }
        if (!same) {
            throw RunFailure(FailureReason.IDENTITY_MISMATCH, "The session shows '$shown' but this agent is '$expected'.")
        }
        return "The session shows '$expected'"
    }

    /** Awaits the newest e-mail code for this agent and stores it as `{vars.email_code}`. */
    suspend fun awaitEmailCode(trace: RunTrace): String {
        val identity = trace.runtime.identity
        val mail =
            trace.act("await e-mail code for ${identity.email}") {
                verification.await(
                    identity.email,
                    trace.runtime.runStartedAt,
                    MailPurpose.CODE,
                    settings.mailTimeout,
                    settings.mailPollInterval,
                )
            }
        val code =
            mail.code?.takeIf { it.isNotBlank() }
                ?: throw RunFailure(FailureReason.MAIL_TIMEOUT, "The verification e-mail for ${identity.email} contains no code.")
        trace.runtime.variables[AgentVariableKeys.EMAIL_CODE] = code
        return code
    }

    /** Waits until the page leaves [from] (and is recognised), or returns the state it is stuck in. */
    suspend fun awaitTransition(
        trace: RunTrace,
        from: PageState,
    ): PageState =
        trace.probe(
            "wait for the page to leave ${from.label}",
            { withTimeoutOrNull(settings.transitionTimeout) { pollUntilMoved(trace, from) } ?: detect(trace) },
        ) { it != from && it != PageState.OTHER }

    suspend fun submitLogin(trace: RunTrace) {
        trace.open("login")
        trace.fill("login.email", trace.runtime.identity.email)
        trace.fillPassword("login.password")
        trace.click("login.submit")
    }

    /** Asks the test API up to [RunFunctionSettings.oracleAttempts] times: it may lag a moment behind the UI. */
    suspend fun <T : Any> retryOracle(block: suspend () -> T?): T? {
        repeat(settings.oracleAttempts) { attempt ->
            block()?.let { return it }
            if (attempt < settings.oracleAttempts - 1) delay(settings.oracleRetryDelay)
        }
        return null
    }

    private suspend fun confirmEmailCode(trace: RunTrace): PageState {
        submitEmailCode(trace, awaitEmailCode(trace))
        val first = awaitTransition(trace, PageState.EMAIL_CODE)
        if (first != PageState.EMAIL_CODE) return first
        trace.note("e-mail code rejected; waiting once for a newer code", StepStatus.FAILED)
        val newer =
            try {
                awaitEmailCode(trace)
            } catch (e: MailTimeoutException) {
                throw RunFailure(FailureReason.OTP_REJECTED, "The e-mail code was rejected and no newer code arrived within ${e.timeout}.")
            }
        submitEmailCode(trace, newer)
        val state = awaitTransition(trace, PageState.EMAIL_CODE)
        if (state == PageState.EMAIL_CODE) {
            throw RunFailure(FailureReason.OTP_REJECTED, "The e-mail code was rejected twice (${trace.currentUrl()}).")
        }
        return state
    }

    private suspend fun submitEmailCode(
        trace: RunTrace,
        code: String,
    ) {
        trace.fill("verify.code", code)
        trace.click("verify.submit")
    }

    private suspend fun confirmPhoneCode(trace: RunTrace): PageState {
        if (!oracle.isAvailable) {
            throw RunFailure(
                FailureReason.MISSING_PREREQUISITE,
                "The site asks for a phone code, which is only readable through the test API (/test/otp), and it is not available.",
            )
        }
        val phone = trace.runtime.identity.phone
        val code =
            trace.act("read the phone code for $phone from the test API") { retryOracle { oracle.latestOtp(phone) } }
                ?: throw RunFailure(FailureReason.REGISTRATION_FAILED, "The test API has no phone code for $phone.")
        trace.fill("verify.phone_code", code)
        trace.click("verify.phone_submit")
        val state = awaitTransition(trace, PageState.PHONE_CODE)
        if (state == PageState.PHONE_CODE) throw RunFailure(FailureReason.OTP_REJECTED, "The phone code for $phone was rejected.")
        return state
    }

    private suspend fun loginFromLoginPage(trace: RunTrace): PageState {
        submitLogin(trace)
        val state = awaitTransition(trace, PageState.LOGIN_PAGE)
        if (state == PageState.LOGIN_PAGE) {
            val why = loginError(trace) ?: "still on the login page"
            throw RunFailure(FailureReason.LOGIN_FAILED, "Login as ${trace.runtime.identity.email} failed: $why")
        }
        return state
    }

    private suspend fun loginError(trace: RunTrace): String? =
        if (trace.isVisible("login.error")) trace.readText("login.error")?.trim()?.takeIf { it.isNotEmpty() } else null

    private suspend fun pollUntilMoved(
        trace: RunTrace,
        from: PageState,
    ): PageState {
        while (true) {
            val state = detect(trace)
            if (state != from && state != PageState.OTHER) return state
            delay(settings.pollInterval)
        }
    }

    private suspend fun detect(trace: RunTrace): PageState =
        when {
            trace.isVisible(USER_NAME) -> PageState.LOGGED_IN
            trace.isVisible("verify.phone_code") -> PageState.PHONE_CODE
            trace.isVisible("verify.code") -> PageState.EMAIL_CODE
            trace.isVisible("login.email") -> PageState.LOGIN_PAGE
            else -> PageState.OTHER
        }

    companion object {
        const val USER_NAME = "session.user_name"

        fun normalizeName(name: String): String = Normalizer.normalize(name.trim().replace(WHITESPACE, " "), Normalizer.Form.NFC)

        private val WHITESPACE = Regex("\\s+")
    }
}
