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

package az.petek.app.panel.explorer

import az.petek.app.config.ResolvedAccount
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserContextLostException
import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.FlowNames
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.TargetProfile
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plays the target profile's `login` flow for the explorer with an owner account, so a form that asks for more than an
 * e-mail and a password (a company code, a workspace, a consent box) signs in the way the testers' flow does. The
 * explorer has no tester run around it, so only the page steps are played: `goto`, `fill`, `select`, `check`, `click`,
 * `click_if_visible`, `wait_for`, `expect_url`, `if_visible`, `assert_identity` (waited for) and `save_session` (the
 * caller saves). A flow with a step that needs a tester's run (an e-mail or phone code, `read`, a journey) is
 * [Outcome.Unsupported], and so is a flow whose template names a value the account does not have.
 *
 * Templates: `{self.email}`, `{self.password}` (only in `fill` values), `{self.name}`, `{self.role}`, and every value of
 * the account's `fields` as `{self.<name>}`, `{shared.<name>}` or `{vars.<name>}`. Selector and path references follow
 * the profile as in the testers' flows; the profile's `dismiss` overlays are clicked away before every step.
 */
internal class ExplorerLoginFlow(
    private val profile: TargetProfile,
    private val stepTimeout: Duration = DEFAULT_TIMEOUT,
) {
    sealed interface Outcome {
        data object Played : Outcome

        /** The profile keeps the contract's default login, which the caller's plain form signs in the same way. */
        data object ContractDefault : Outcome

        /** The flow cannot be played here; [reason] says why, and the caller signs in with the plain form instead. */
        data class Unsupported(
            val reason: String,
        ) : Outcome

        /** A step of the flow failed on the page; [reason] is what the owner reads. */
        data class Failed(
            val reason: String,
        ) : Outcome
    }

    suspend fun play(
        session: BrowserSession,
        account: ResolvedAccount,
    ): Outcome {
        val flow = profile.flow(FlowNames.LOGIN)
        if (flow == null || flow == TargetProfile.DEFAULT_FLOWS[FlowNames.LOGIN]) return Outcome.ContractDefault
        unsupported(flow.steps)?.let { return Outcome.Unsupported("its step '$it' needs a tester's run") }
        val values = Values(account)
        missing(flow.steps, values)?.let { return Outcome.Unsupported(it) }
        return try {
            steps(session, flow.steps, values)
            Outcome.Played
        } catch (e: StepFailed) {
            Outcome.Failed(e.message.orEmpty())
        } catch (e: BrowserContextLostException) {
            throw e
        } catch (e: BrowserActionException) {
            Outcome.Failed(e.message.orEmpty())
        }
    }

    private suspend fun steps(
        session: BrowserSession,
        steps: List<FlowStep>,
        values: Values,
    ) {
        for (step in steps) {
            dismiss(session)
            when (step) {
                is FlowStep.Goto -> {
                    goto(session, values.render(step.path).trim())
                }

                is FlowStep.Fill -> {
                    session.fillSelector(selector(step.selector, values), values.render(step.value, secretAllowed = true))
                }

                is FlowStep.Select -> {
                    session.selectSelector(selector(step.selector, values), values.render(step.option))
                }

                is FlowStep.Check -> {
                    check(session, selector(step.selector, values))
                }

                is FlowStep.Click -> {
                    session.clickSelector(selector(step.selector, values))
                }

                is FlowStep.ClickIfVisible -> {
                    clickIfVisible(session, selector(step.selector, values))
                }

                is FlowStep.WaitFor -> {
                    waitFor(session, step, values)
                }

                is FlowStep.ExpectUrl -> {
                    expectUrl(session, step)
                }

                is FlowStep.IfVisible -> {
                    if (visibleWithin(session, selector(step.selector, values), step.timeout)) steps(session, step.then, values)
                }

                is FlowStep.AssertIdentity -> {
                    waitForAny(session, listOf(selector(step.selector, values)), null, stepTimeout, "the signed-in user is not shown")
                }

                FlowStep.SaveSession -> {
                    // The caller saves the session once the sign-in is confirmed.
                }

                else -> {
                    error("unsupported step ${step.key}")
                }
            }
        }
    }

    private suspend fun goto(
        session: BrowserSession,
        rendered: String,
    ) {
        val path = profile.resolvePath(rendered)
        if (!(path.startsWith("/") && !path.startsWith("//")) && !WEB_ADDRESS.containsMatchIn(path)) {
            throw StepFailed("cannot open '$rendered': it is neither a path on the target nor a web address")
        }
        session.navigate(path)
    }

    private suspend fun check(
        session: BrowserSession,
        selector: String,
    ) {
        val checked = session.readAttribute(selector, "aria-checked") == "true" || session.readAttribute(selector, "checked") != null
        if (!checked) session.clickSelector(selector)
    }

    private suspend fun clickIfVisible(
        session: BrowserSession,
        selector: String,
    ) {
        if (session.isSelectorVisible(selector)) session.clickSelector(selector)
    }

    private suspend fun dismiss(session: BrowserSession) {
        profile.dismiss.forEach { clickIfVisible(session, profile.resolveSelector(it)) }
    }

    private suspend fun waitFor(
        session: BrowserSession,
        step: FlowStep.WaitFor,
        values: Values,
    ) {
        val failure = step.failure?.message?.let { values.render(it.replace("{url}", session.currentUrl())) }
        val selectors = step.selectors.map { selector(it, values) }
        waitForAny(session, selectors, step.text?.let { values.render(it) }, step.timeout ?: stepTimeout, failure)
    }

    private suspend fun waitForAny(
        session: BrowserSession,
        selectors: List<String>,
        text: String?,
        timeout: Duration,
        failure: String?,
    ) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (true) {
            if (selectors.any { session.isSelectorVisible(it) } || (text != null && session.isTextVisible(text))) return
            if (System.nanoTime() >= deadline) {
                throw StepFailed(failure ?: "waited $timeout for ${(selectors + listOfNotNull(text)).joinToString(" or ")}")
            }
            delay(POLL)
        }
    }

    private suspend fun expectUrl(
        session: BrowserSession,
        step: FlowStep.ExpectUrl,
    ) {
        val regex = Regex(step.regex)
        val deadline = System.nanoTime() + (step.timeout ?: stepTimeout).inWholeNanoseconds
        while (!regex.containsMatchIn(session.currentUrl())) {
            if (System.nanoTime() >= deadline) throw StepFailed(step.failure?.message ?: "the page did not reach ${step.regex}")
            delay(POLL)
        }
    }

    private suspend fun visibleWithin(
        session: BrowserSession,
        selector: String,
        timeout: Duration?,
    ): Boolean {
        if (timeout == null) return session.isSelectorVisible(selector)
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (session.isSelectorVisible(selector)) return true
            delay(POLL)
        }
        return session.isSelectorVisible(selector)
    }

    /** A flow's selector reference with its templates filled, as the profile resolves it (see the testers' flows). */
    private fun selector(
        reference: String,
        values: Values,
    ): String = profile.resolveSelector(values.render(reference))

    /** The first step, anywhere in the flow, that only a tester's run can play. */
    private fun unsupported(steps: List<FlowStep>): String? =
        steps.firstNotNullOfOrNull { step ->
            when (step) {
                is FlowStep.IfVisible -> unsupported(step.then)

                is FlowStep.Goto, is FlowStep.Fill, is FlowStep.Select, is FlowStep.Check, is FlowStep.Click,
                is FlowStep.ClickIfVisible, is FlowStep.WaitFor, is FlowStep.ExpectUrl, is FlowStep.AssertIdentity,
                FlowStep.SaveSession,
                -> null

                else -> step.key
            }
        }

    /** A value the flow's templates need that the account does not have, as the owner can fix it. */
    private fun missing(
        steps: List<FlowStep>,
        values: Values,
    ): String? {
        val unknown = steps.flatMap(::templates).flatMap(values::unknown).distinct()
        return unknown.takeIf { it.isNotEmpty() }?.let { names ->
            "the login flow needs ${names.joinToString { "{$it}" }}; give the account `fields:` with " +
                names.joinToString { it.substringAfter('.') } + " in its target profile"
        }
    }

    /** Every template text of [step] (nested steps included) that the player fills in. */
    private fun templates(step: FlowStep): List<String> =
        when (step) {
            is FlowStep.Goto -> listOf(step.path)
            is FlowStep.Fill -> listOf(step.selector, step.value)
            is FlowStep.Select -> listOf(step.selector, step.option)
            is FlowStep.Check -> listOf(step.selector)
            is FlowStep.Click -> listOf(step.selector)
            is FlowStep.ClickIfVisible -> listOf(step.selector)
            is FlowStep.WaitFor -> step.selectors + listOfNotNull(step.text)
            is FlowStep.IfVisible -> listOf(step.selector) + step.then.flatMap(::templates)
            is FlowStep.AssertIdentity -> listOf(step.selector)
            else -> emptyList()
        }

    /** Template values of one account; [unknown] lists the placeholders it cannot fill. */
    private class Values(
        private val account: ResolvedAccount,
    ) {
        fun render(
            template: String,
            secretAllowed: Boolean = false,
        ): String =
            PLACEHOLDER.replace(template) { match ->
                val (scope, name) = match.destructured
                if (scope == "self" && name == "password") {
                    if (!secretAllowed) throw StepFailed("{self.password} is only allowed in a fill value")
                    return@replace account.password?.reveal().orEmpty()
                }
                value(scope, name) ?: match.value
            }

        fun unknown(template: String): List<String> =
            PLACEHOLDER
                .findAll(template)
                .map { it.destructured }
                .filter { (scope, name) -> !(scope == "self" && name == "password") && value(scope, name) == null }
                .map { (scope, name) -> "$scope.$name" }
                .toList()

        private fun value(
            scope: String,
            name: String,
        ): String? =
            when {
                scope == "self" && name == "email" -> account.email
                scope == "self" && name == "name" -> account.name
                scope == "self" && name == "role" -> account.role
                scope in setOf("self", "shared", "vars") -> account.fields[name]
                scope == "campaign" && name == "company" -> account.fields["company"]
                else -> null
            }
    }

    private class StepFailed(
        message: String,
    ) : RuntimeException(message)

    private companion object {
        val DEFAULT_TIMEOUT = 20.seconds
        val POLL = 250.milliseconds
        val PLACEHOLDER = Regex("""\{([a-z]+)\.([a-z0-9_]+)\}""")
        val WEB_ADDRESS = Regex("^https?://", RegexOption.IGNORE_CASE)
    }
}
