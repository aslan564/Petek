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
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.Flow
import az.petek.campaign.domain.FlowFailure
import az.petek.campaign.domain.FlowNames
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.FlowStep.Click
import az.petek.campaign.domain.FlowStep.EmailCode
import az.petek.campaign.domain.FlowStep.Fill
import az.petek.campaign.domain.FlowStep.Goto
import az.petek.campaign.domain.FlowStep.IfVisible
import az.petek.campaign.domain.FlowStep.SaveSession
import az.petek.campaign.domain.FlowStep.WaitFor
import az.petek.campaign.domain.TargetProfile
import az.petek.core.security.Secret
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ExplorerLoginFlowTest {
    private val email = "[data-testid=\"login-email\"]"
    private val password = "[data-testid=\"login-password\"]"
    private val submit = "[data-testid=\"login-submit\"]"
    private val userName = "[data-testid=\"current-user-name\"]"

    /** A login form that also asks for the company's code, as KadroHR's does. */
    private val companyLogin =
        listOf(
            Goto("login"),
            WaitFor(listOf("login.email"), null),
            Fill("login.email", "{self.email}"),
            Fill("login.password", "{self.password}"),
            Fill("login.company_code", "{shared.company_code}"),
            Click("login.submit"),
            WaitFor(listOf("session.user_name"), null, failure = FlowFailure(message = "Login as {self.email} failed")),
            SaveSession,
        )

    private fun profile(
        steps: List<FlowStep>,
        dismiss: List<String> = emptyList(),
    ) = TargetProfile.DEFAULT.copy(
        selectors = mapOf("login.company_code" to "#companyCode"),
        flows = TargetProfile.DEFAULT_FLOWS + (FlowNames.LOGIN to Flow(steps)),
        dismiss = dismiss,
    )

    private val account =
        ResolvedAccount("admin", "owner@example.com", Secret("s3cret-password"), null, fields = mapOf("company_code" to "ACME-42"))

    /** A page that shows the form, and the signed-in user's name once the form is sent. */
    private fun page() =
        FakeBrowserSession().apply {
            visibleSelectors += email
            onAction = { if (it == "clickSelector $submit") visibleSelectors += userName }
        }

    @Test
    fun `the profile's own login flow is played with the account's fields`() =
        runBlocking<Unit> {
            val page = page()

            ExplorerLoginFlow(profile(companyLogin)).play(page, account) shouldBe ExplorerLoginFlow.Outcome.Played

            page.actions shouldContainExactly
                listOf(
                    "navigate /login",
                    "fillSelector $email=owner@example.com",
                    "fillSelector $password=s3cret-password",
                    "fillSelector #companyCode=ACME-42",
                    "clickSelector $submit",
                )
        }

    @Test
    fun `a value the account lacks leaves the flow unplayed and names the field to give`() =
        runBlocking<Unit> {
            val page = page()

            val outcome = ExplorerLoginFlow(profile(companyLogin)).play(page, account.copy(fields = emptyMap()))

            val reason = outcome.shouldBeInstanceOf<ExplorerLoginFlow.Outcome.Unsupported>().reason
            reason shouldContain "{shared.company_code}"
            reason shouldContain "`fields:` with company_code"
            page.actions.shouldBeEmpty()
        }

    @Test
    fun `a flow that needs a tester's run is left to the plain form, even inside a condition`() =
        runBlocking<Unit> {
            val page = page()
            val steps = companyLogin + IfVisible("verify.code", listOf(EmailCode("verify.code", "verify.submit")))

            ExplorerLoginFlow(profile(steps)).play(page, account) shouldBe
                ExplorerLoginFlow.Outcome.Unsupported("its step 'email_code' needs a tester's run")
            page.actions.shouldBeEmpty()
        }

    @Test
    fun `the contract's default login is left to the plain form`() =
        runBlocking<Unit> {
            ExplorerLoginFlow(TargetProfile.DEFAULT).play(page(), account) shouldBe ExplorerLoginFlow.Outcome.ContractDefault
        }

    @Test
    fun `a page that never shows what the flow waits for fails with the flow's own message`() =
        runBlocking<Unit> {
            val page = page().apply { onAction = {} }

            ExplorerLoginFlow(profile(companyLogin), stepTimeout = 300.milliseconds).play(page, account) shouldBe
                ExplorerLoginFlow.Outcome.Failed("Login as owner@example.com failed")
        }

    @Test
    fun `a step the page refuses ends the flow as failed`() =
        runBlocking<Unit> {
            val page = page().apply { failOn = { it.startsWith("clickSelector") } }

            val outcome = ExplorerLoginFlow(profile(companyLogin)).play(page, account)

            outcome.shouldBeInstanceOf<ExplorerLoginFlow.Outcome.Failed>().reason shouldContain "clickSelector $submit"
        }

    @Test
    fun `the password is typed only into a field, never into a path or a selector`() =
        runBlocking<Unit> {
            val page = page()
            val steps = listOf(Goto("/login?p={self.password}")) + companyLogin

            ExplorerLoginFlow(profile(steps)).play(page, account) shouldBe
                ExplorerLoginFlow.Outcome.Failed("{self.password} is only allowed in a fill value")
            page.actions.shouldBeEmpty()
        }

    @Test
    fun `the profile's overlays are clicked away before the steps`() =
        runBlocking<Unit> {
            val page =
                page().apply {
                    visibleSelectors += "#consent-ok"
                    onAction = { action ->
                        if (action == "clickSelector #consent-ok") visibleSelectors -= "#consent-ok"
                        if (action == "clickSelector $submit") visibleSelectors += userName
                    }
                }

            ExplorerLoginFlow(profile(companyLogin, dismiss = listOf("#consent-ok"))).play(page, account) shouldBe
                ExplorerLoginFlow.Outcome.Played

            page.actions.take(2) shouldContainExactly listOf("clickSelector #consent-ok", "navigate /login")
        }
}
