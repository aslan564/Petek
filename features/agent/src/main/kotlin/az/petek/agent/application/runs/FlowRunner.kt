package az.petek.agent.application.runs

import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.browser.domain.BrowserActionException
import az.petek.campaign.domain.Flow
import az.petek.campaign.domain.FlowFailure
import az.petek.campaign.domain.FlowFailureReason
import az.petek.campaign.domain.FlowNames
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.JourneyPage
import az.petek.campaign.domain.LinkPurpose
import az.petek.campaign.domain.ValueTarget
import az.petek.evidence.domain.StepStatus
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Executes a [Flow] of the campaign's target profile on the agent's own browser (see [Flow] for the step language):
 * one recorded RUN sub-action per browser action, the profile's `dismiss` overlays clicked away before every step,
 * templates rendered right before their step, a screenshot of the page when the flow fails. Failures end the flow as a
 * [RunFailure] with the step's own [FlowFailure] or the caller's default reason; browser, inbox and test API errors
 * propagate unchanged, so [RunEngine] reports them the same way for every run function.
 *
 * What a flow achieved is written to [FlowProgress] as it happens, so a caller that retries (`register_and_login`)
 * still knows, after a failure, whether the account was created already.
 *
 * Stateless between executions; one instance serves every agent of the run.
 */
internal class FlowRunner(
    private val oracle: TargetOracle,
    private val verification: AwaitVerificationUseCase,
    private val flows: TargetFlows,
    private val settings: RunFunctionSettings,
) {
    /**
     * Runs the profile's flow [name]. [defaultReason] files a failing step that names no reason of its own;
     * [company] is `{campaign.company}`.
     */
    suspend fun run(
        trace: RunTrace,
        name: String,
        progress: FlowProgress,
        defaultReason: FailureReason,
        company: String = RegisterOwnerRunFunction.DEFAULT_COMPANY,
    ) {
        val flow =
            trace.runtime.target.flow(name)
                ?: throw RunFailure(FailureReason.MISSING_PREREQUISITE, "The target profile has no flow '$name'.")
        val execution = Execution(trace, name, progress, defaultReason, company)
        try {
            execution.steps(flow.steps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace.captureScreenshot()
            throw e
        }
    }

    /**
     * Finishes a sign-up: runs the `login` flow when [loginFirst] or when the page shows no signed-in user, then
     * `verify_identity` unless a flow already asserted the identity, and saves the session unless a flow saved it.
     * With the contract flows a registration ends signed in and checked, so nothing is left to do; a site that sends
     * its new users to the login page (KadroHR) signs them in here.
     */
    suspend fun completeSignIn(
        trace: RunTrace,
        progress: FlowProgress,
        loginFirst: Boolean = false,
    ) {
        if (loginFirst || !trace.isVisible(TargetFlows.USER_NAME)) run(trace, FlowNames.LOGIN, progress, FailureReason.LOGIN_FAILED)
        if (progress.identityShown == null) run(trace, FlowNames.VERIFY_IDENTITY, progress, FailureReason.LOGIN_FAILED)
        if (!progress.sessionSaved) {
            trace.saveStorageState()
            progress.sessionSaved = true
        }
    }

    /** One flow execution for one agent. */
    private inner class Execution(
        private val trace: RunTrace,
        private val flowName: String,
        private val progress: FlowProgress,
        private val defaultReason: FailureReason,
        private val company: String,
    ) {
        private val runtime get() = trace.runtime
        private val templates = FlowTemplates(trace, flowName, company, settings)

        suspend fun steps(steps: List<FlowStep>) {
            steps.forEach { step(it) }
        }

        private suspend fun step(step: FlowStep) {
            dismissOverlays()
            when (step) {
                is FlowStep.Goto -> goto(step)
                is FlowStep.Fill -> fill(step)
                is FlowStep.Select -> trace.select(ref(step.selector), templates.render(step.option))
                is FlowStep.Check -> check(ref(step.selector))
                is FlowStep.Click -> trace.click(ref(step.selector))
                is FlowStep.ClickIfVisible -> clickIfVisible(ref(step.selector))
                is FlowStep.WaitFor -> waitFor(step)
                is FlowStep.ExpectUrl -> expectUrl(step)
                is FlowStep.EmailLink -> emailLink(step)
                is FlowStep.EmailCode -> emailCode(ref(step.selector), step.submit?.let { ref(it) })
                is FlowStep.PhoneCode -> phoneCode(ref(step.selector), step.submit?.let { ref(it) })
                is FlowStep.Read -> read(step)
                is FlowStep.SetShared -> setShared(step)
                is FlowStep.IfVisible -> ifVisible(step)
                is FlowStep.SaveSession -> saveSession()
                is FlowStep.AccountCreated -> progress.accountCreated = true
                is FlowStep.AssertIdentity -> progress.identityShown = flows.verifyIdentity(trace, ref(step.selector))
                is FlowStep.Journey -> Journey(step).run()
            }
        }

        /** A selector reference with its templates rendered. */
        private suspend fun ref(template: String): String = templates.render(template)

        private suspend fun dismissOverlays() {
            runtime.target.dismiss.forEach { overlay ->
                if (!trace.isVisible(overlay)) return@forEach
                try {
                    trace.act("dismiss ${trace.describe(overlay)}") { runtime.session.clickSelector(trace.selector(overlay)) }
                } catch (_: BrowserActionException) {
                    // Recorded as ERROR; an overlay that vanished before the click is no reason to fail the flow.
                }
            }
        }

        private suspend fun goto(step: FlowStep.Goto) {
            val rendered = templates.render(step.path).trim()
            val path = runtime.target.resolvePath(rendered)
            if (path.startsWith("/") && !path.startsWith("//")) return trace.open(path)
            if (WEB_ADDRESS.containsMatchIn(path)) return trace.openUrl(path)
            throw RunFailure(
                defaultReason,
                "Flow '$flowName' cannot open '$rendered': it is neither a path on the target nor a web address.",
            )
        }

        private suspend fun fill(step: FlowStep.Fill) {
            val selector = ref(step.selector)
            val value = templates.render(step.value)
            if (FlowTemplates.PASSWORD in step.value) trace.fillMasked(selector, value) else trace.fill(selector, value)
        }

        private suspend fun check(selector: String) {
            val checked = trace.attribute(selector, "aria-checked") == "true" || trace.attribute(selector, "checked") != null
            if (checked) {
                trace.note("check ${trace.describe(selector)}", StepStatus.PASSED, "already checked")
            } else {
                trace.act("check ${trace.describe(selector)}") { runtime.session.clickSelector(trace.selector(selector)) }
            }
        }

        private suspend fun clickIfVisible(selector: String) {
            if (trace.isVisible(selector)) {
                trace.click(selector)
            } else {
                trace.note("click ${trace.describe(selector)} if visible", StepStatus.PASSED, "not visible, skipped")
            }
        }

        private suspend fun waitFor(step: FlowStep.WaitFor) {
            val timeout = step.timeout ?: settings.uiTimeout
            val text = step.text?.let { templates.render(it) }
            val selectors = step.selectors.map { ref(it) }
            val found =
                when {
                    text != null -> {
                        trace.probe("wait for text ${quoted(text)}", { runtime.session.waitForText(text, timeout).found }) { it }
                    }

                    selectors.size == 1 -> {
                        trace.waitFor(selectors.single(), timeout)
                    }

                    else -> {
                        val description = "wait for " + selectors.joinToString(" | ") { trace.describe(it) }
                        trace.probe(description, { anyVisibleWithin(selectors, timeout) }) { it }
                    }
                }
            if (!found) {
                val what = text?.let { "text ${quoted(it)}" } ?: selectors.joinToString(" or ") { trace.describe(it) }
                fail(step.failure, step.key, "$what did not appear within $timeout (${trace.currentUrl()})")
            }
        }

        private suspend fun expectUrl(step: FlowStep.ExpectUrl) {
            val timeout = step.timeout ?: settings.transitionTimeout
            val pattern = Regex(step.regex)
            val reached =
                trace.probe("expect url ${quoted(step.regex)}", {
                    withTimeoutOrNull(timeout) { pollUntil { pattern.containsMatchIn(trace.currentUrl()) } } ?: false
                }) { it }
            if (!reached) {
                fail(
                    step.failure,
                    step.key,
                    "the page did not reach ${quoted(step.regex)} within $timeout (still on ${trace.currentUrl()})",
                )
            }
        }

        private suspend fun emailLink(step: FlowStep.EmailLink) {
            val identity = runtime.identity
            val kind = MAIL_KIND.getValue(step.purpose)
            val known =
                if (step.purpose == LinkPurpose.INVITE) {
                    stored(step.target) ?: runtime.shared.get(SharedRunState.inviteLink(identity.email))
                } else {
                    null
                }
            val link =
                known ?: trace
                    .act("await the $kind e-mail for ${identity.email}") { awaitLinkMail(step.pattern) }
                    .link
                    ?.toString()
                    ?: throw RunFailure(defaultReason, "The $kind e-mail for ${identity.email} contains no link.")
            store(step.target, link)
            if (step.open) trace.openUrl(link)
        }

        private suspend fun awaitLinkMail(pattern: String?) =
            if (pattern == null) {
                verification.await(
                    runtime.identity.email,
                    runtime.runStartedAt,
                    MailPurpose.LINK,
                    settings.mailTimeout,
                    settings.mailPollInterval,
                )
            } else {
                verification.awaitLink(
                    runtime.identity.email,
                    runtime.runStartedAt,
                    Regex(pattern),
                    settings.mailTimeout,
                    settings.mailPollInterval,
                )
            }

        /**
         * Types the newest e-mail code; with [submit], a code whose field is still shown afterwards is rejected: one
         * newer code is awaited and tried, a second rejection is `otp_rejected`.
         */
        private suspend fun emailCode(
            selector: String,
            submit: String?,
        ) {
            trace.fill(selector, flows.awaitEmailCode(trace))
            if (submit == null) return
            trace.click(submit)
            if (leaves(selector)) return
            trace.note("e-mail code rejected; waiting once for a newer code", StepStatus.FAILED)
            val newer =
                try {
                    flows.awaitEmailCode(trace)
                } catch (e: MailTimeoutException) {
                    throw RunFailure(
                        FailureReason.OTP_REJECTED,
                        "The e-mail code was rejected and no newer code arrived within ${e.timeout}.",
                    )
                }
            trace.fill(selector, newer)
            trace.click(submit)
            val accepted = leaves(selector)
            if (!accepted) throw RunFailure(FailureReason.OTP_REJECTED, "The e-mail code was rejected twice (${trace.currentUrl()}).")
        }

        private suspend fun phoneCode(
            selector: String,
            submit: String?,
        ) {
            if (!oracle.isAvailable) {
                throw RunFailure(
                    FailureReason.MISSING_PREREQUISITE,
                    "The site asks for a phone code, which is only readable through the test API (/test/otp), and it is not available.",
                )
            }
            val phone = runtime.identity.phone
            val code =
                trace.lookup("read the phone code for $phone from the test API") { flows.retryOracle { oracle.latestOtp(phone) } }
                    ?: throw RunFailure(FailureReason.REGISTRATION_FAILED, "The test API has no phone code for $phone.")
            runtime.variables[AgentVariableKeys.PHONE_CODE] = code
            trace.fill(selector, code)
            if (submit == null) return
            trace.click(submit)
            val accepted = leaves(selector)
            if (!accepted) throw RunFailure(FailureReason.OTP_REJECTED, "The phone code for $phone was rejected.")
        }

        /** Whether [selector] disappears within the transition timeout (the site accepted what was submitted). */
        private suspend fun leaves(selector: String): Boolean =
            trace.probe("wait for the page to leave ${trace.describe(selector)}", {
                withTimeoutOrNull(settings.transitionTimeout) { pollUntil { !trace.isVisible(selector) } } ?: false
            }) { it }

        private suspend fun read(step: FlowStep.Read) {
            val selector = ref(step.selector)
            val element = trace.selector(selector)
            val text =
                trace.lookup("read ${trace.describe(selector)}") {
                    runtime.session
                        .readText(element)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: runtime.session
                            .readAttribute(element, "value")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                } ?: throw RunFailure(defaultReason, "${trace.describe(selector)} shows nothing to read (${trace.currentUrl()}).")
            val value =
                step.regex?.let { regex ->
                    val match =
                        Regex(regex).find(text)
                            ?: throw RunFailure(
                                defaultReason,
                                "${trace.describe(selector)} shows '$text', which does not match ${quoted(regex)}.",
                            )
                    if (match.groupValues.size > 1) match.groupValues[1] else match.value
                } ?: text
            store(step.into, value)
        }

        private suspend fun setShared(step: FlowStep.SetShared) {
            val value = templates.render(step.value)
            runtime.shared.put(step.sharedKey, value)
            trace.note("publish shared.${step.sharedKey}", StepStatus.PASSED, quoted(value))
        }

        private suspend fun ifVisible(step: FlowStep.IfVisible) {
            val selector = ref(step.selector)
            val visible = step.timeout?.let { anyVisibleWithin(listOf(selector), it) } ?: trace.isVisible(selector)
            trace.note("if ${trace.describe(selector)} is visible", StepStatus.PASSED, if (visible) "visible" else "not visible, skipped")
            if (visible) steps(step.then)
        }

        private suspend fun saveSession() {
            trace.saveStorageState()
            progress.sessionSaved = true
        }

        private fun stored(target: ValueTarget): String? =
            when (target.scope) {
                ValueTarget.Scope.VARS -> runtime.variables[target.key]
                ValueTarget.Scope.SHARED -> runtime.shared.get(target.key)
            }

        private fun store(
            target: ValueTarget,
            value: String,
        ) {
            when (target.scope) {
                ValueTarget.Scope.VARS -> runtime.variables[target.key] = value
                ValueTarget.Scope.SHARED -> runtime.shared.put(target.key, value)
            }
        }

        /**
         * Fails the flow at the step [stepKey] as [failure] says: its reason (else the default one), its message (else a
         * sentence naming the flow, the step and [detail]) and, with an error element, what that element shows.
         */
        private suspend fun fail(
            failure: FlowFailure?,
            stepKey: String,
            detail: String,
        ): Nothing {
            val reason = failure?.reason?.let(::reasonOf) ?: defaultReason
            val message = failure?.message?.let { templates.render(it, allowUrl = true) }
            val shown = failure?.error?.let { errorText(ref(it)) }
            val text =
                when {
                    message == null -> {
                        "Flow '$flowName' failed at $stepKey: $detail" +
                            (shown?.let { "; the page says ${quoted(it)}" } ?: "") +
                            "."
                    }

                    failure.error != null -> {
                        "$message: ${shown ?: detail}"
                    }

                    else -> {
                        message
                    }
                }
            throw RunFailure(reason, text)
        }

        private suspend fun errorText(selector: String): String? =
            if (trace.isVisible(selector)) trace.readText(selector)?.trim()?.takeIf { it.isNotEmpty() } else null

        private suspend fun anyVisibleWithin(
            selectors: List<String>,
            timeout: Duration,
        ): Boolean =
            withTimeoutOrNull(timeout) { pollUntil { selectors.any { trace.isVisible(it) } } } ?: selectors.any { trace.isVisible(it) }

        private suspend fun pollUntil(condition: suspend () -> Boolean): Boolean {
            while (!condition()) delay(settings.pollInterval)
            return true
        }

        /** A [FlowStep.Journey]: see its KDoc for the rules this enforces. */
        private inner class Journey(
            private val journey: FlowStep.Journey,
        ) {
            private val until = journey.until

            suspend fun run() {
                val visits = mutableMapOf<String, Int>()
                var state = startState()
                while (state != State.Done) {
                    val page =
                        (state as? State.On)?.page
                            ?: throw RunFailure(
                                FailureReason.REGISTRATION_FAILED,
                                "Unexpected page while ${journey.label}: ${trace.currentUrl()}.",
                            )
                    val visit = visits.merge(page.label, 1, Int::plus) ?: 1
                    if (visit > journey.maxVisits) {
                        throw RunFailure(reasonOf(page.reason), "The site asked for ${page.label} $visit times (${trace.currentUrl()}).")
                    }
                    steps(page.steps)
                    val next = leave(page)
                    if (next == State.On(page)) page.stuck?.let { fail(it, journey.key, "still on ${page.label}") }
                    state = next
                }
            }

            /** The page named `start`, acted on without looking; otherwise whatever known page the site shows first. */
            private suspend fun startState(): State {
                val start = journey.start?.trim() ?: return firstPage()
                return State.On(journey.pages.first { it.label.trim() == start })
            }

            private suspend fun firstPage(): State =
                trace.probeDescribed(
                    { withTimeoutOrNull(settings.transitionTimeout) { awaitRecognised(except = null) } ?: detect() },
                    { "wait for ${trace.describe(until)} or a page of ${journey.label}" },
                ) { it != State.Unknown }

            /** Waits until the page is [State.Done] or another known page than [page]; after the timeout, what is shown. */
            private suspend fun leave(page: JourneyPage): State =
                trace.probeDescribed(
                    { withTimeoutOrNull(settings.transitionTimeout) { awaitRecognised(except = page) } ?: detect() },
                    { found -> leaving(page, found) },
                ) { it != State.On(page) && it != State.Unknown }

            /** `wait for session.user_name` when the journey ended, else what was waited for. */
            private fun leaving(
                page: JourneyPage,
                found: State?,
            ): String = if (found == State.Done) "wait for ${trace.describe(until)}" else "wait for the page to leave ${page.label}"

            private suspend fun awaitRecognised(except: JourneyPage?): State {
                while (true) {
                    val state = detect()
                    if (state != State.Unknown && (except == null || state != State.On(except))) return state
                    delay(settings.pollInterval)
                }
            }

            /** [until] first, then the pages in their order; nothing known is [State.Unknown]. */
            private suspend fun detect(): State {
                if (trace.isVisible(ref(until))) return State.Done
                return journey.pages.firstOrNull { trace.isVisible(ref(it.selector)) }?.let(State::On) ?: State.Unknown
            }
        }
    }

    private sealed interface State {
        data object Done : State

        data object Unknown : State

        data class On(
            val page: JourneyPage,
        ) : State
    }

    private companion object {
        val WEB_ADDRESS = Regex("^https?://", RegexOption.IGNORE_CASE)

        /** How the e-mail of each link purpose reads in evidence and messages. */
        val MAIL_KIND: Map<LinkPurpose, String> =
            mapOf(LinkPurpose.INVITE to "invitation", LinkPurpose.VERIFY to "verification", LinkPurpose.ANY to "link")

        fun reasonOf(reason: FlowFailureReason): FailureReason = FailureReason.entries.first { it.key == reason.key }

        fun quoted(text: String): String = "\"" + text.replace("\n", " ") + "\""
    }
}

/** What flows achieved for one run function execution, updated step by step (see [FlowRunner]). */
internal class FlowProgress {
    /** The site accepted a registration (`account_created`): a retry signs in instead of registering again. */
    var accountCreated: Boolean = false

    /** What `assert_identity` saw (`The session shows '…'`); null when no flow checked the identity. */
    var identityShown: String? = null

    /** Whether a flow saved the session (`save_session`). */
    var sessionSaved: Boolean = false
}
