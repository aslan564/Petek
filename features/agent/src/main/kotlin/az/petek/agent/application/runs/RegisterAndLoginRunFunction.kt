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

package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.PlusAddressRefusal
import az.petek.agent.domain.StepContext
import az.petek.campaign.domain.FlowNames
import az.petek.core.model.RegistrationMode
import az.petek.evidence.domain.StepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * `register_and_login` (managers and employees): the target profile's join flow for the identity's
 * [RegistrationMode] (`join_by_invite` or `join_by_code`), then the `login` flow when the page shows nobody signed in,
 * the identity check and the saved storage state (see [FlowRunner.completeSignIn]).
 *
 * The contract flows: INVITE follows the link published by `seed_company` (or the invitation e-mail) to the invitation
 * form (name, phone, password); COMPANY_CODE waits for the published company code (up to
 * [RunFunctionSettings.companyCodeTimeout]) and fills the `/join` form (code, name, e-mail, phone, password,
 * department). On a site without companies the tester's gate decides: SELF follows `sign_up`, LOGIN signs in with
 * the owner's account it was given (no sign-up), GUEST only opens the home page. All of them then pass the sign-in journey: e-mail code (one newer code on rejection, then `otp_rejected`),
 * phone code when asked, login when the site lands on the login page.
 *
 * The whole flow is tried up to [RunFunctionSettings.registrationAttempts] times (docs/PLAN.md). Once the site accepted
 * the registration (the flow passed `account_created`, or finished) the account exists, so later attempts sign in with
 * the `login` flow instead of registering again. A missing prerequisite, an unreachable test inbox or an identity
 * mismatch is not retried: the first cannot heal by retrying, the second was already retried for the whole mail
 * timeout, the third is a finding that a retry would hide.
 */
internal class RegisterAndLoginRunFunction(
    private val engine: RunEngine,
    private val flows: FlowRunner,
    private val settings: RunFunctionSettings,
) : RunFunction {
    override val name: String = RunFunctions.REGISTER_AND_LOGIN

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome = engine.execute(name, runtime, step) { joinWithRetries() }

    /** What earlier attempts of one execution achieved. */
    private class Attempts {
        var accountCreated = false
    }

    private suspend fun RunTrace.joinWithRetries(): ActionOutcome {
        val identity = runtime.identity
        if (identity.registration == RegistrationMode.OWNER) {
            throw RunFailure(
                FailureReason.MISSING_PREREQUISITE,
                "register_and_login is for invited or company-code testers; the owner ${identity.agentId} signs up with register_owner.",
            )
        }
        if (identity.registration == RegistrationMode.GUEST) {
            runtime.session.navigate(runtime.target.path(HOME))
            return succeeded("${identity.agentId} is a visitor (gate guest): nothing to sign up for or sign in to.")
        }
        val attempts = Attempts()
        if (identity.registration == RegistrationMode.LOGIN) attempts.accountCreated = true
        var last: ActionOutcome? = null
        for (attempt in 1..settings.registrationAttempts) {
            if (attempt > 1) delay(settings.retryDelay)
            val resume = if (attempts.accountCreated) " (account exists: signing in)" else ""
            note("attempt $attempt of ${settings.registrationAttempts}$resume", StepStatus.PASSED)
            val outcome = outcomeOf { joinOnce(identity.registration, attempts) }
            if (outcome.succeeded || outcome.failureReason in FINAL) return outcome
            note("attempt $attempt failed", StepStatus.FAILED, outcome.summary)
            last = outcome
        }
        val failure = checkNotNull(last)
        val plusHint = if (refusesPlusAddress(identity.email)) " ${PlusAddressRefusal.HINT}" else ""
        return failure.copy(
            summary = "Registration failed after ${settings.registrationAttempts} attempts; last: ${failure.summary}$plusHint",
        )
    }

    /** Whether the page the failed registration left shows an e-mail error for the tester's `+` address. */
    private suspend fun RunTrace.refusesPlusAddress(email: String): Boolean =
        '+' in email &&
            try {
                PlusAddressRefusal.detect(email, runtime.session.snapshot().visibleText)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }

    private suspend fun RunTrace.joinOnce(
        mode: RegistrationMode,
        attempts: Attempts,
    ): ActionOutcome {
        val progress = FlowProgress()
        val signInOnly = attempts.accountCreated
        if (!signInOnly) {
            try {
                flows.run(this, joinFlow(mode), progress, FailureReason.REGISTRATION_FAILED)
            } finally {
                if (progress.accountCreated) attempts.accountCreated = true
            }
            attempts.accountCreated = true
        }
        flows.completeSignIn(this, progress, loginFirst = signInOnly)
        val shown = progress.identityShown ?: "The session was checked"
        return succeeded(
            if (mode ==
                RegistrationMode.LOGIN
            ) {
                "Signed in with the owner's account. $shown."
            } else {
                "Joined by ${label(mode)} and signed in. $shown."
            },
        )
    }

    private fun joinFlow(mode: RegistrationMode): String =
        when (mode) {
            RegistrationMode.INVITE -> FlowNames.JOIN_BY_INVITE
            RegistrationMode.COMPANY_CODE -> FlowNames.JOIN_BY_CODE
            RegistrationMode.SELF -> FlowNames.SIGN_UP
            else -> error("${mode.key} testers do not sign up here")
        }

    private fun label(mode: RegistrationMode) =
        when (mode) {
            RegistrationMode.INVITE -> "invitation"
            RegistrationMode.COMPANY_CODE -> "company code"
            RegistrationMode.SELF -> "sign-up"
            else -> mode.key
        }

    private companion object {
        const val HOME = "home"

        /** Failures a new attempt cannot fix, or must not hide. */
        val FINAL = setOf(FailureReason.MISSING_PREREQUISITE, FailureReason.MAIL_UNAVAILABLE, FailureReason.IDENTITY_MISMATCH)
    }
}
