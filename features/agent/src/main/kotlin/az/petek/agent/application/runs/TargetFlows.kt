package az.petek.agent.application.runs

import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.FailureReason
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import kotlinx.coroutines.delay
import java.text.Normalizer

/**
 * Pieces of the sign-in journey shared by the run functions and the flow steps ([FlowRunner]): the newest e-mail code,
 * the identity check that proves whose session the browser holds, and patient test API lookups.
 */
internal class TargetFlows(
    private val verification: AwaitVerificationUseCase,
    private val settings: RunFunctionSettings,
) {
    /**
     * Proves the session belongs to this agent: the text of [userName] (a selector reference) equals the identity's
     * display name (trimmed, whitespace collapsed, Unicode NFC). This is the session-isolation check of docs/PLAN.md.
     */
    suspend fun verifyIdentity(
        trace: RunTrace,
        userName: String = USER_NAME,
    ): String {
        val expected = normalizeName(trace.runtime.identity.displayName)
        if (!trace.waitFor(userName, settings.uiTimeout)) {
            throw RunFailure(FailureReason.LOGIN_FAILED, "Not signed in: no current user name on ${trace.currentUrl()}.")
        }
        val shown = trace.readText(userName)?.let(::normalizeName).orEmpty()
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

    /** Asks the test API up to [RunFunctionSettings.oracleAttempts] times: it may lag a moment behind the UI. */
    suspend fun <T : Any> retryOracle(block: suspend () -> T?): T? {
        repeat(settings.oracleAttempts) { attempt ->
            block()?.let { return it }
            if (attempt < settings.oracleAttempts - 1) delay(settings.oracleRetryDelay)
        }
        return null
    }

    companion object {
        /** Selector key of the signed-in user's name: the "logged in" sign every run function relies on. */
        const val USER_NAME = "session.user_name"

        fun normalizeName(name: String): String = Normalizer.normalize(name.trim().replace(WHITESPACE, " "), Normalizer.Form.NFC)

        private val WHITESPACE = Regex("\\s+")
    }
}
