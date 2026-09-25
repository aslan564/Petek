package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.StepContext
import az.petek.core.model.RegistrationMode
import az.petek.evidence.domain.StepStatus
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import kotlinx.coroutines.delay

/**
 * `register_and_login` (managers and employees), following the identity's [RegistrationMode]:
 * - INVITE: the link published by `seed_company` (or the invitation e-mail) -> invitation form (name, phone, password);
 * - COMPANY_CODE: wait for the published company code (up to [RunFunctionSettings.companyCodeTimeout]) -> `/join`
 *   form (code, name, e-mail, phone, password, department).
 *
 * Then the shared journey: e-mail code (one newer code on rejection, then `otp_rejected`), phone code when asked,
 * login when the site lands on the login page, storage state saved, identity verified.
 *
 * The whole flow is tried up to [RunFunctionSettings.registrationAttempts] times (docs/PLAN.md). Once a form was
 * accepted the account exists, so later attempts sign in instead of registering again. A missing prerequisite, an
 * unreachable test inbox or an identity mismatch is not retried: the first cannot heal by retrying, the second was
 * already retried for the whole mail timeout, the third is a finding that a retry would hide.
 */
internal class RegisterAndLoginRunFunction(
    private val engine: RunEngine,
    private val flows: TargetFlows,
    private val verification: AwaitVerificationUseCase,
    private val settings: RunFunctionSettings,
) : RunFunction {
    override val name: String = RunFunctions.REGISTER_AND_LOGIN

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome = engine.execute(name, runtime, step) { joinWithRetries() }

    /** What earlier attempts of one execution achieved. */
    private class Progress {
        var accountCreated = false
        var inviteLink: String? = null
    }

    private suspend fun RunTrace.joinWithRetries(): ActionOutcome {
        val identity = runtime.identity
        if (identity.registration == RegistrationMode.OWNER) {
            throw RunFailure(
                FailureReason.MISSING_PREREQUISITE,
                "register_and_login is for invited or company-code testers; the owner ${identity.agentId} signs up with register_owner.",
            )
        }
        val progress = Progress()
        var last: ActionOutcome? = null
        for (attempt in 1..settings.registrationAttempts) {
            if (attempt > 1) delay(settings.retryDelay)
            val resume = if (progress.accountCreated) " (account exists: signing in)" else ""
            note("attempt $attempt of ${settings.registrationAttempts}$resume", StepStatus.PASSED)
            val outcome = outcomeOf { joinOnce(identity.registration, progress) }
            if (outcome.succeeded || outcome.failureReason in FINAL) return outcome
            note("attempt $attempt failed", StepStatus.FAILED, outcome.summary)
            last = outcome
        }
        val failure = checkNotNull(last)
        return failure.copy(summary = "Registration failed after ${settings.registrationAttempts} attempts; last: ${failure.summary}")
    }

    private suspend fun RunTrace.joinOnce(
        mode: RegistrationMode,
        progress: Progress,
    ): ActionOutcome {
        val start = if (progress.accountCreated) PageState.LOGIN_PAGE else submitRegistration(mode, progress)
        if (start == PageState.OTHER) {
            throw RunFailure(FailureReason.REGISTRATION_FAILED, "The ${label(mode)} form was not accepted (still on ${currentUrl()}).")
        }
        progress.accountCreated = true
        flows.signIn(this, start)
        saveStorageState()
        val shown = flows.verifyIdentity(this)
        return succeeded("Joined by ${label(mode)} and signed in. $shown.")
    }

    private suspend fun RunTrace.submitRegistration(
        mode: RegistrationMode,
        progress: Progress,
    ): PageState =
        when (mode) {
            RegistrationMode.INVITE -> acceptInvitation(progress)
            RegistrationMode.COMPANY_CODE -> joinWithCompanyCode()
            RegistrationMode.OWNER -> error("owners are rejected before the first attempt")
        }

    private suspend fun RunTrace.acceptInvitation(progress: Progress): PageState {
        val identity = runtime.identity
        val link =
            progress.inviteLink
                ?: runtime.shared.get(SharedRunState.inviteLink(identity.email))
                ?: act("await the invitation e-mail for ${identity.email}") {
                    verification.await(
                        identity.email,
                        runtime.runStartedAt,
                        MailPurpose.LINK,
                        settings.mailTimeout,
                        settings.mailPollInterval,
                    )
                }.link?.toString()
                ?: throw RunFailure(FailureReason.REGISTRATION_FAILED, "The invitation e-mail for ${identity.email} contains no link.")
        progress.inviteLink = link
        openUrl(link)
        if (!waitFor("invite.name", settings.uiTimeout)) {
            throw RunFailure(FailureReason.REGISTRATION_FAILED, "The invitation page shows no sign-up form (${currentUrl()}).")
        }
        fill("invite.name", identity.displayName)
        fill("invite.phone", identity.phone)
        fillPassword("invite.password")
        click("invite.submit")
        return flows.awaitTransition(this, PageState.OTHER)
    }

    private suspend fun RunTrace.joinWithCompanyCode(): PageState {
        val identity = runtime.identity
        val code =
            runtime.shared.get(SharedRunState.COMPANY_CODE)
                ?: lookup("wait for the company code") { runtime.shared.await(SharedRunState.COMPANY_CODE, settings.companyCodeTimeout) }
                ?: throw RunFailure(
                    FailureReason.MISSING_PREREQUISITE,
                    "No company code was published within ${settings.companyCodeTimeout}; did seed_company run?",
                )
        open("join")
        if (!waitFor("join.code", settings.uiTimeout)) {
            throw RunFailure(FailureReason.REGISTRATION_FAILED, "The join page shows no form (${currentUrl()}).")
        }
        fill("join.code", code)
        fill("join.name", identity.displayName)
        fill("join.email", identity.email)
        fill("join.phone", identity.phone)
        fillPassword("join.password")
        identity.department?.let { select("join.department", it) }
        click("join.submit")
        return flows.awaitTransition(this, PageState.OTHER)
    }

    private fun label(mode: RegistrationMode) =
        when (mode) {
            RegistrationMode.INVITE -> "invitation"
            RegistrationMode.COMPANY_CODE -> "company code"
            RegistrationMode.OWNER -> "owner sign-up"
        }

    private companion object {
        /** Failures a new attempt cannot fix, or must not hide. */
        val FINAL = setOf(FailureReason.MISSING_PREREQUISITE, FailureReason.MAIL_UNAVAILABLE, FailureReason.IDENTITY_MISMATCH)
    }
}
