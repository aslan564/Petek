/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.RunFunctionFixture
import az.petek.agent.testing.ScriptedSite
import az.petek.campaign.domain.Flow
import az.petek.campaign.domain.FlowFailure
import az.petek.campaign.domain.FlowFailureReason
import az.petek.campaign.domain.FlowNames
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.FlowStep.AccountCreated
import az.petek.campaign.domain.FlowStep.AssertIdentity
import az.petek.campaign.domain.FlowStep.Check
import az.petek.campaign.domain.FlowStep.Click
import az.petek.campaign.domain.FlowStep.ClickIfVisible
import az.petek.campaign.domain.FlowStep.EmailLink
import az.petek.campaign.domain.FlowStep.ExpectUrl
import az.petek.campaign.domain.FlowStep.Fill
import az.petek.campaign.domain.FlowStep.Goto
import az.petek.campaign.domain.FlowStep.IfVisible
import az.petek.campaign.domain.FlowStep.Read
import az.petek.campaign.domain.FlowStep.SaveSession
import az.petek.campaign.domain.FlowStep.Select
import az.petek.campaign.domain.FlowStep.SetShared
import az.petek.campaign.domain.FlowStep.WaitFor
import az.petek.campaign.domain.JourneyPage
import az.petek.campaign.domain.LinkPurpose
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.ValueTarget
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.StepStatus
import az.petek.identity.domain.Identity
import az.petek.mail.domain.MailPurpose
import az.petek.oracle.domain.TestCompany
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Flows of a site that is not the contract, modelled on the real KadroHR (docs/KADROHR_READINESS.md): login with a
 * company code, sign-up confirmed by an e-mail link, invitations that set a password, overlays, interstitial pages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowRunnerTest {
    private val owner = AgentTestData.admin
    private val invited = AgentTestData.itManager
    private val employee = AgentTestData.itEmployee

    private fun fixture(
        identity: Identity,
        vararg flows: Pair<String, Flow>,
        dismiss: List<String> = emptyList(),
    ): Pair<RunFunctionFixture, ScriptedSite> {
        val target = TargetProfile.DEFAULT.copy(selectors = SELECTORS, flows = TargetProfile.DEFAULT_FLOWS + flows, dismiss = dismiss)
        val fixture = RunFunctionFixture(identity, target = target, contractSite = false)
        return fixture to ScriptedSite(fixture.browser).also { it.loginPage(fixture) }
    }

    /** The login page, the home page and a login that needs the password and the company code [CODE]. */
    private fun ScriptedSite.loginPage(fixture: RunFunctionFixture) {
        page("/login", LOGIN_EMAIL, LOGIN_PASSWORD, LOGIN_CODE, SUBMIT)
        page("/home", USER_NAME, texts = mapOf(USER_NAME to "  ${fixture.identity.displayName} "))
        on("clickSelector $SUBMIT") {
            if (!browser.url.startsWith("/login")) return@on
            val password = fixture.identity.password.reveal()
            if (fixture.lastFill(LOGIN_PASSWORD) == password && fixture.lastFill(LOGIN_CODE) == CODE) {
                show("/home")
            } else {
                browser.visibleSelectors += ALERT
                browser.selectorTexts[ALERT] = "Şifrə və ya şirkət kodu yanlışdır"
            }
        }
    }

    private fun RunFunctionFixture.lastFill(selector: String): String? =
        browser.actions.lastOrNull { it.startsWith("fillSelector $selector=") }?.substringAfter("fillSelector $selector=")

    @Test
    fun `a custom login types the company code the admin published and waits for the signed-in user`() =
        runTest {
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to KADRO_LOGIN)
            fixture.shared.put(SharedRunState.COMPANY_CODE, CODE)

            val outcome = fixture.run("login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.actions("login") shouldContainExactly
                listOf(
                    "login: open /login",
                    "login: fill login.email \"${employee.email}\"",
                    "login: fill login.password \"***\"",
                    "login: fill login.company_code \"$CODE\"",
                    "login: click login.submit",
                    "login: wait for session.user_name",
                    "login: save storage state",
                )
            fixture.browser.actions shouldContain "fillSelector $LOGIN_CODE=$CODE"
            fixture.storageStateSaved() shouldBe true
            fixture.assertPasswordNotRecorded()
        }

    @Test
    fun `a refused custom login reports the site's alert under the flow's reason and keeps a screenshot of it`() =
        runTest {
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to KADRO_LOGIN)
            fixture.shared.put(SharedRunState.COMPANY_CODE, "WRONG123")

            val outcome = fixture.run("login")

            outcome.failureReason shouldBe FailureReason.LOGIN_FAILED
            outcome.summary shouldBe "Login as ${employee.email} failed: Şifrə və ya şirkət kodu yanlışdır"
            val waited = fixture.steps.single { it.action == "login: read login.error" }
            val screenshots = fixture.artifactsOf(ArtifactType.SCREENSHOT).map { it.stepId }
            screenshots shouldContainExactly listOf(waited.stepId, fixture.steps.last().stepId)
            fixture.storageStateSaved() shouldBe false
        }

    @Test
    fun `a shared value that is not published yet is awaited before it is typed`() =
        runTest {
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to KADRO_LOGIN)
            launch {
                delay(2.minutes)
                fixture.shared.put(SharedRunState.COMPANY_CODE, CODE)
            }

            fixture.run("login").status shouldBe ActionStatus.SUCCEEDED

            currentTime shouldBe 120_000
            fixture.steps.single { it.action == "login: wait for the company code" }.status shouldBe StepStatus.PASSED
        }

    @Test
    fun `a failing step without a failure of its own names the flow, the step and what went wrong`() =
        runTest {
            val login = Flow(listOf(Goto("/login"), WaitFor(listOf("#dashboard"), null, 5.seconds)))
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to login)

            val outcome = fixture.run("login")

            outcome.failureReason shouldBe FailureReason.LOGIN_FAILED
            outcome.summary shouldBe "Flow 'login' failed at wait_for: #dashboard did not appear within 5s (/login)."
        }

    @Test
    fun `a failure with a message and no error element uses the message with the current page`() =
        runTest {
            val login =
                Flow(
                    listOf(
                        Goto("/login"),
                        ExpectUrl(
                            "/home",
                            3.seconds,
                            FlowFailure(FlowFailureReason.REGISTRATION_FAILED, "Stuck on {url} as {self.first_name}"),
                        ),
                    ),
                )
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to login)

            val outcome = fixture.run("login")

            outcome.failureReason shouldBe FailureReason.REGISTRATION_FAILED
            outcome.summary shouldBe "Stuck on /login as Cəmil"
            currentTime shouldBe 3_000
        }

    @Test
    fun `overlays are dismissed before the step they would block and a vanished overlay does not fail the flow`() =
        runTest {
            val (fixture, site) = fixture(employee, FlowNames.LOGIN to KADRO_LOGIN, dismiss = listOf("#consent-ok", "#chat-close"))
            fixture.shared.put(SharedRunState.COMPANY_CODE, CODE)
            site.page("/login", LOGIN_EMAIL, LOGIN_PASSWORD, LOGIN_CODE, SUBMIT, "#consent-ok", "#chat-close")
            site.on("clickSelector #consent-ok") { fixture.browser.visibleSelectors -= "#consent-ok" }
            fixture.browser.failOn = { it == "clickSelector #chat-close" }

            val outcome = fixture.run("login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.steps.count { it.action == "login: dismiss #consent-ok" } shouldBe 1
            fixture.steps
                .filter { it.action == "login: dismiss #chat-close" }
                .map { it.status }
                .toSet() shouldBe setOf(StepStatus.ERROR)
        }

    @Test
    fun `an owner on a KadroHR-like site signs up, confirms by link and signs in with the published company code`() =
        runTest {
            val (fixture, site) = fixture(owner, FlowNames.REGISTER_OWNER to KADRO_SIGN_UP, FlowNames.LOGIN to KADRO_LOGIN)
            var verified = false
            site.page(
                "/register",
                "#first",
                "#last",
                "#email",
                "#password",
                "#confirm",
                "#company",
                "#reg-country",
                HYBRID,
                "#code",
                SUBMIT,
            )
            site.page("/register/verify", visibleTexts = setOf("Email-inizi yoxlayın"))
            site.page("/register/complete", visibleTexts = setOf("Tamamlandı"))
            site.page("/api/v1/registration/verify", visibleTexts = setOf("Email Təsdiqləndi"))
            fixture.browser.attributes["#code" to "value"] = CODE
            site.on("clickSelector $SUBMIT") {
                when {
                    fixture.browser.url == "/register" -> site.show("/register/verify")
                    fixture.lastFill(LOGIN_CODE) == CODE -> site.show("/home")
                }
            }
            site.on("navigate $VERIFY_LINK") {
                verified = true
                site.show(VERIFY_LINK)
            }
            site.on("navigate /register/verify") { site.show(if (verified) "/register/complete" else "/register/verify") }
            fixture.verification.sendLink(owner.email, "https://kadrohr.test/help")
            fixture.verification.sendLink(owner.email, VERIFY_LINK)
            fixture.oracle.companies["c9"] = TestCompany("c9", "Pətək Test MMC", CODE, isTest = true)
            fixture.oracle.owners[owner.email.lowercase()] = "c9"
            fixture.oracle.owners[owner.email] = "c9"

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldBe
                "Registered ${owner.email} as owner of 'Pətək Test MMC'. The session shows 'Əli Kərimov'. Company id c9."
            outcome.objectId shouldBe "c9"
            fixture.browser.actions shouldContainAll
                listOf(
                    "fillSelector #first=Əli",
                    "fillSelector #last=Kərimov",
                    "fillSelector #confirm=${owner.password.reveal()}",
                    "fillSelector #company=Pətək Test MMC",
                    "selectSelector #reg-country=Azərbaycan",
                    "clickSelector $HYBRID",
                    "navigate $VERIFY_LINK",
                    "fillSelector $LOGIN_CODE=$CODE",
                )
            fixture.verification.linkPatterns shouldContainExactly listOf("registration/verify\\?token=")
            fixture.shared.get(SharedRunState.COMPANY_CODE) shouldBe CODE
            fixture.actions("register_owner") shouldContainAll
                listOf(
                    "register_owner: check $HYBRID",
                    "register_owner: click #cookie-ok if visible",
                    "register_owner: read #code",
                    "register_owner: expect url \"/register/verify\"",
                    "register_owner: await the verification e-mail for ${owner.email}",
                    "register_owner: wait for text \"Tamamlandı\"",
                    "register_owner: fill login.company_code \"$CODE\"",
                    "register_owner: current user is 'Əli Kərimov'",
                )
            fixture.storageStateSaved() shouldBe true
            fixture.assertPasswordNotRecorded()
        }

    @Test
    fun `an invited tester on a KadroHR-like site sets a password from the invitation link and then signs in`() =
        runTest {
            val (fixture, site) = fixture(invited, FlowNames.JOIN_BY_INVITE to KADRO_INVITE, FlowNames.LOGIN to KADRO_LOGIN)
            fixture.shared.put(SharedRunState.COMPANY_CODE, CODE)
            site.page("/api/v1/auth/set-password", "#password", "#confirmPassword", "#submitBtn")
            site.on("clickSelector #submitBtn") { fixture.browser.visibleTexts += "Parol uğurla təyin edildi" }
            fixture.verification.sendLink(invited.email, INVITE_LINK)

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldBe "Joined by invitation and signed in. The session shows 'Vəli Həsənov'."
            fixture.verification.calls shouldContainExactly listOf(invited.email to MailPurpose.LINK)
            fixture.verification.linkPatterns shouldContainExactly listOf("set-password\\?token=")
            fixture.runtime.variables["invite_link"] shouldBe INVITE_LINK
            fixture.browser.actions shouldContainAll
                listOf("navigate $INVITE_LINK", "fillSelector #confirmPassword=${invited.password.reveal()}", "navigate /login")
            fixture.storageStateSaved() shouldBe true
            fixture.assertPasswordNotRecorded()
        }

    @Test
    fun `after the account_created marker a failed attempt is retried by signing in, not by joining again`() =
        runTest {
            val join = Flow(listOf(Goto("/join"), Click("#join"), AccountCreated, WaitFor(listOf("#welcome"), null, 2.seconds)))
            val (fixture, site) = fixture(employee, FlowNames.JOIN_BY_CODE to join, FlowNames.LOGIN to KADRO_LOGIN)
            fixture.shared.put(SharedRunState.COMPANY_CODE, CODE)
            site.page("/join", "#join")

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.actions("register_and_login").filter { "attempt" in it } shouldContainExactly
                listOf(
                    "register_and_login: attempt 1 of 3",
                    "register_and_login: attempt 1 failed",
                    "register_and_login: attempt 2 of 3 (account exists: signing in)",
                )
            fixture.browser.actions.count { it == "navigate /join" } shouldBe 1
        }

    @Test
    fun `without the marker a join flow that fails is joined again on every attempt`() =
        runTest {
            val join = Flow(listOf(Goto("/join"), WaitFor(listOf("#never"), null, 2.seconds)))
            val (fixture, _) = fixture(employee, FlowNames.JOIN_BY_CODE to join)

            val outcome = fixture.run("register_and_login")

            outcome.failureReason shouldBe FailureReason.REGISTRATION_FAILED
            outcome.summary shouldBe
                "Registration failed after 3 attempts; last: Flow 'join_by_code' failed at wait_for: #never did not appear within 2s (/join)."
            fixture.browser.actions.count { it == "navigate /join" } shouldBe 3
        }

    @Test
    fun `verify_identity reports what its assert_identity step saw`() =
        runTest {
            val (fixture, site) = fixture(employee, FlowNames.VERIFY_IDENTITY to Flow(listOf(Goto("/home"), AssertIdentity("#me"))))
            site.page("/home", "#me", texts = mapOf("#me" to employee.displayName))

            val outcome = fixture.run("verify_identity")

            outcome.summary shouldBe "The session shows 'Cəmil Əliyev'."
            fixture.actions("verify_identity") shouldContain "verify_identity: wait for #me"
        }

    @Test
    fun `a sign-up that asserted the identity with its own selector is not sent through the login flow again`() =
        runTest {
            val signUp = Flow(listOf(Goto("/register"), Click("#go"), AssertIdentity("#me")))
            val (fixture, site) = fixture(owner, FlowNames.REGISTER_OWNER to signUp, FlowNames.LOGIN to KADRO_LOGIN)
            site.page("/register", "#go")
            site.page("/welcome", "#me", texts = mapOf("#me" to owner.displayName))
            site.on("clickSelector #go") { site.show("/welcome") }

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "The session shows '${owner.displayName}'."
            fixture.browser.actions.filter { it.startsWith("navigate") } shouldContainExactly listOf("navigate /register")
            fixture.actions("register_owner").count { it.startsWith("register_owner: current user is") } shouldBe 1
            fixture.storageStateSaved() shouldBe true
        }

    @Test
    fun `a sign-up that ends signed out is signed in with the login flow and then checked`() =
        runTest {
            val signUp = Flow(listOf(Goto("/register"), Click("#go"), WaitFor(emptyList(), "Hesab yaradıldı")))
            val (fixture, site) = fixture(owner, FlowNames.REGISTER_OWNER to signUp, FlowNames.LOGIN to KADRO_LOGIN)
            fixture.shared.put(SharedRunState.COMPANY_CODE, CODE)
            site.page("/register", "#go")
            site.page("/done", visibleTexts = setOf("Hesab yaradıldı"))
            site.on("clickSelector #go") { site.show("/done") }

            val outcome = fixture.run("register_owner")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.browser.actions.filter { it.startsWith("navigate") } shouldContainExactly
                listOf("navigate /register", "navigate /login")
            fixture.actions("register_owner") shouldContainAll
                listOf("register_owner: wait for session.user_name", "register_owner: current user is '${owner.displayName}'")
            fixture.storageStateSaved() shouldBe true
        }

    @Test
    fun `the owner's company argument is also the company of the login flow that finishes the sign-up`() =
        runTest {
            val signUp = Flow(listOf(Goto("/register"), Fill("#company", "{campaign.company}"), Click("#go")))
            val login =
                Flow(
                    listOf(Goto("/tenant-login"), Fill("#tenant", "{campaign.company}"), Click("#enter"), WaitFor(listOf(USER_NAME), null)),
                )
            val (fixture, site) = fixture(owner, FlowNames.REGISTER_OWNER to signUp, FlowNames.LOGIN to login)
            site.page("/register", "#company", "#go")
            site.page("/tenant-login", "#tenant", "#enter")
            site.on("clickSelector #enter") { site.show("/home") }

            val outcome = fixture.run("register_owner", args = mapOf("company" to "Kadro Sınaq MMC"))

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.browser.actions shouldContainAll listOf("fillSelector #company=Kadro Sınaq MMC", "fillSelector #tenant=Kadro Sınaq MMC")
        }

    @Test
    fun `an invitation link another tester stored under a shared key is never reused`() =
        runTest {
            val join =
                Flow(
                    listOf(
                        EmailLink(LinkPurpose.INVITE, "set-password\\?token=", open = false, into = ValueTarget.shared("last_invite")),
                        AccountCreated,
                    ),
                )
            val (fixture, _) = fixture(invited, FlowNames.JOIN_BY_INVITE to join, FlowNames.LOGIN to KADRO_LOGIN)
            fixture.shared.put(SharedRunState.COMPANY_CODE, CODE)
            fixture.shared.put("last_invite", "https://api.kadrohr.test/api/v1/auth/set-password?token=someone-else")
            fixture.verification.sendLink(invited.email, INVITE_LINK)

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.verification.calls shouldContainExactly listOf(invited.email to MailPurpose.LINK)
            fixture.shared.get("last_invite") shouldBe INVITE_LINK
        }

    @Test
    fun `a value no earlier step stored is a missing prerequisite`() =
        runTest {
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to Flow(listOf(Goto("/login"), Fill("#x", "{vars.nope}"))))

            val outcome = fixture.run("login")

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldBe "Flow 'login': {vars.nope} is not set: no earlier step of this tester stored it."
        }

    @Test
    fun `a shared value of another flow that never appears names who should publish it`() =
        runTest {
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to Flow(listOf(Fill("#x", "{shared.plan_name}"))))

            val outcome = fixture.run("login", timeout = 30.minutes)

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldBe "No plan name was published within 5m; a set_shared or read step of an earlier flow must publish it."
        }

    @Test
    fun `stored values flow from one step to the next, in this tester and across the run`() =
        runTest {
            val login =
                Flow(
                    listOf(
                        Goto("/login"),
                        Read("#banner", ValueTarget.vars("greeting"), "Salam, (\\p{L}+)"),
                        SetShared("last_greeting", "{vars.greeting} ({self.agent_id})"),
                        EmailLink(LinkPurpose.ANY, pattern = "/docs/", open = false, into = ValueTarget.shared("manual")),
                        Goto("{shared.manual}"),
                    ),
                )
            val (fixture, site) = fixture(employee, FlowNames.LOGIN to login)
            site.page("/login", "#banner", texts = mapOf("#banner" to "Salam, Cəmil! Xoş gəldiniz"))
            fixture.verification.sendLink(employee.email, "https://kadrohr.test/docs/start")

            fixture.run("login").status shouldBe ActionStatus.SUCCEEDED

            fixture.runtime.variables["greeting"] shouldBe "Cəmil"
            fixture.shared.get("last_greeting") shouldBe "Cəmil (a04)"
            fixture.shared.get("manual") shouldBe "https://kadrohr.test/docs/start"
            fixture.browser.actions.filter { it.startsWith("navigate") } shouldContainExactly
                listOf("navigate /login", "navigate https://kadrohr.test/docs/start")
            fixture.steps.single { it.action == "login: publish shared.last_greeting" }.detail shouldBe "\"Cəmil (a04)\""
        }

    @Test
    fun `a read that shows nothing or does not match fails the flow`() =
        runTest {
            val (empty, emptySite) =
                fixture(
                    employee,
                    FlowNames.LOGIN to Flow(listOf(Goto("/login"), Read("#none", ValueTarget.vars("x")))),
                )
            emptySite.page("/login", "#none")
            empty.run("login").summary shouldBe "#none shows nothing to read (/login)."

            val (mismatch, site) =
                fixture(
                    employee,
                    FlowNames.LOGIN to Flow(listOf(Goto("/login"), Read("#t", ValueTarget.vars("x"), "\\d+"))),
                )
            site.page("/login", "#t", texts = mapOf("#t" to "heç nə"))
            mismatch.run("login").summary shouldBe "#t shows 'heç nə', which does not match \"\\d+\"."
        }

    @Test
    fun `if_visible runs its steps only when the element shows up in time`() =
        runTest {
            val login =
                Flow(
                    listOf(
                        Goto("/login"),
                        IfVisible("#promo", listOf(Click("#promo-close"))),
                        IfVisible("#late", listOf(Click("#late-ok")), timeout = 5.seconds),
                    ),
                )
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to login)
            launch {
                delay(2.seconds)
                fixture.browser.visibleSelectors += "#late"
            }

            fixture.run("login").status shouldBe ActionStatus.SUCCEEDED

            fixture.browser.actions shouldNotContain "clickSelector #promo-close"
            fixture.browser.actions shouldContain "clickSelector #late-ok"
            fixture.steps.single { it.action == "login: if #promo is visible" }.detail shouldBe "not visible, skipped"
        }

    @Test
    fun `wait_for any waits until one of its selectors shows`() =
        runTest {
            val login = Flow(listOf(Goto("/login"), WaitFor(listOf("#a", "#b"), null, 10.seconds)))
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to login)
            launch {
                delay(3.seconds)
                fixture.browser.visibleSelectors += "#b"
            }

            fixture.run("login").status shouldBe ActionStatus.SUCCEEDED

            currentTime shouldBe 3_000
            fixture.steps.single { it.action == "login: wait for #a | #b" }.status shouldBe StepStatus.PASSED
        }

    @Test
    fun `check clicks only a box that is not checked yet`() =
        runTest {
            val login = Flow(listOf(Goto("/login"), Check("#terms"), Check("#news")))
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to login)
            fixture.browser.attributes["#news" to "aria-checked"] = "true"

            fixture.run("login")

            fixture.browser.actions shouldContain "clickSelector #terms"
            fixture.browser.actions shouldNotContain "clickSelector #news"
            fixture.steps.single { it.action == "login: check #news" }.detail shouldBe "already checked"
        }

    @Test
    fun `click_if_visible clicks what is shown and notes what is not`() =
        runTest {
            val login = Flow(listOf(Goto("/login"), ClickIfVisible(SUBMIT), ClickIfVisible("#nothing")))
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to login)

            fixture.run("login")

            fixture.browser.actions shouldContain "clickSelector $SUBMIT"
            fixture.steps.single { it.action == "login: click #nothing if visible" }.detail shouldBe "not visible, skipped"
        }

    @Test
    fun `goto refuses what is neither a target path nor a web address`() =
        runTest {
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to Flow(listOf(Goto("{vars.where}"))))
            fixture.runtime.variables["where"] = "mailto:x@example.test"

            val outcome = fixture.run("login")

            outcome.failureReason shouldBe FailureReason.LOGIN_FAILED
            outcome.summary shouldContain "cannot open 'mailto:x@example.test'"
            fixture.browser.actions shouldBe emptyList()
        }

    @Test
    fun `a custom journey handles an interstitial page and stops a site that keeps asking`() =
        runTest {
            val login =
                Flow(
                    listOf(
                        Goto("/login"),
                        Click(SUBMIT),
                        FlowStep.Journey(
                            label = "entering the app",
                            until = "#home",
                            pages =
                                listOf(
                                    JourneyPage("the terms page", "#terms", listOf(Click("#accept")), FlowFailureReason.LOGIN_FAILED),
                                ),
                        ),
                    ),
                )
            val (fixture, site) = fixture(employee, FlowNames.LOGIN to login)
            var accepted = 0
            site.page("/terms", "#terms", "#accept")
            site.page("/app", "#home")
            site.on("clickSelector $SUBMIT") { site.show("/terms") }
            site.on("clickSelector #accept") { if (++accepted >= 2) site.show("/app") }

            fixture.run("login").status shouldBe ActionStatus.SUCCEEDED
            accepted shouldBe 2
            fixture.actions("login") shouldContainAll
                listOf(
                    "login: wait for #home or a page of entering the app",
                    "login: wait for the page to leave the terms page",
                    "login: wait for #home",
                )

            val (stubborn, stubbornSite) = fixture(employee, FlowNames.LOGIN to login)
            stubbornSite.page("/terms", "#terms", "#accept")
            stubbornSite.on("clickSelector $SUBMIT") { stubbornSite.show("/terms") }

            val outcome = stubborn.run("login")

            outcome.failureReason shouldBe FailureReason.LOGIN_FAILED
            outcome.summary shouldBe "The site asked for the terms page 3 times (/terms)."
        }

    @Test
    fun `an unknown page in a journey is reported with the journey's label under the run function's reason`() =
        runTest {
            val journey =
                FlowStep.Journey(
                    "entering the app",
                    "#home",
                    listOf(JourneyPage("the terms page", "#terms", listOf(Click("#accept")))),
                )
            val (fixture, _) = fixture(employee, FlowNames.LOGIN to Flow(listOf(Goto("/login"), journey)))

            val login = fixture.run("login")

            login.summary shouldBe "Unexpected page while entering the app: /login."
            login.failureReason shouldBe FailureReason.LOGIN_FAILED

            val (joiner, _) = fixture(employee, FlowNames.JOIN_BY_CODE to Flow(listOf(Goto("/join"), journey)))

            val join = joiner.run("register_and_login")

            join.failureReason shouldBe FailureReason.REGISTRATION_FAILED
            join.summary shouldBe "Registration failed after 3 attempts; last: Unexpected page while entering the app: /join."
        }

    @Test
    fun `names are split for forms that ask for first and last name`() {
        FlowTemplates.firstName(" Əli  Vüqar oğlu Məmmədov ") shouldBe "Əli"
        FlowTemplates.lastName(" Əli  Vüqar oğlu Məmmədov ") shouldBe "Vüqar oğlu Məmmədov"
        FlowTemplates.lastName("Məmmədov II") shouldBe "II"
        FlowTemplates.lastName("Aysel") shouldBe "Aysel"
    }

    @Test
    fun `a department placeholder of a tester without department is a missing prerequisite`() =
        runTest {
            val (fixture, _) = fixture(owner, FlowNames.LOGIN to Flow(listOf(Select("#dept", "{self.department}"))))

            val outcome = fixture.run("login")

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldBe "Flow 'login': {self.department} has no value: a01 has no department."
            fixture.steps shouldHaveSize 1
        }

    private companion object {
        const val CODE = "KQ7ZX2M4"
        const val LOGIN_EMAIL = "input[autocomplete=username]"
        const val LOGIN_PASSWORD = "input[autocomplete=current-password]"
        const val LOGIN_CODE = "input[autocomplete=organization]"
        const val SUBMIT = "form button[type=submit]"
        const val ALERT = "role=alert"
        const val USER_NAME = "aside [aria-label=Profil] p.font-medium"
        const val HYBRID = "role=radio[name=\"Hibrid\"]"
        const val VERIFY_LINK = "https://api.kadrohr.test/api/v1/registration/verify?token=a.b.c"
        const val INVITE_LINK = "https://api.kadrohr.test/api/v1/auth/set-password?token=u-1"

        val SELECTORS =
            mapOf(
                "login.email" to LOGIN_EMAIL,
                "login.password" to LOGIN_PASSWORD,
                "login.company_code" to LOGIN_CODE,
                "login.submit" to SUBMIT,
                "login.error" to ALERT,
                "session.user_name" to USER_NAME,
            )

        val KADRO_LOGIN =
            Flow(
                listOf(
                    Goto("login"),
                    Fill("login.email", "{self.email}"),
                    Fill("login.password", "{self.password}"),
                    Fill("login.company_code", "{shared.company_code}"),
                    Click("login.submit"),
                    WaitFor(
                        listOf("session.user_name"),
                        null,
                        20.seconds,
                        FlowFailure(FlowFailureReason.LOGIN_FAILED, "Login as {self.email} failed", "login.error"),
                    ),
                    SaveSession,
                ),
            )

        val KADRO_SIGN_UP =
            Flow(
                listOf(
                    Goto("register"),
                    Fill("#first", "{self.first_name}"),
                    Fill("#last", "{self.last_name}"),
                    Fill("#email", "{self.email}"),
                    Fill("#password", "{self.password}"),
                    Fill("#confirm", "{self.password}"),
                    Fill("#company", "{campaign.company}"),
                    Select("#reg-country", "Azərbaycan"),
                    Check(HYBRID),
                    Read("#code", ValueTarget.shared("company_code"), "([A-Z0-9]{8})"),
                    ClickIfVisible("#cookie-ok"),
                    Click(SUBMIT),
                    ExpectUrl("/register/verify"),
                    AccountCreated,
                    EmailLink(LinkPurpose.VERIFY, "registration/verify\\?token="),
                    Goto("/register/verify"),
                    WaitFor(emptyList(), "Tamamlandı", 90.seconds),
                ),
            )

        val KADRO_INVITE =
            Flow(
                listOf(
                    EmailLink(LinkPurpose.INVITE, "set-password\\?token="),
                    WaitFor(
                        listOf("#password"),
                        null,
                        failure = FlowFailure(FlowFailureReason.REGISTRATION_FAILED, "No password form on {url}"),
                    ),
                    Fill("#password", "{self.password}"),
                    Fill("#confirmPassword", "{self.password}"),
                    Click("#submitBtn"),
                    WaitFor(emptyList(), "Parol uğurla təyin edildi"),
                    AccountCreated,
                ),
            )
    }
}
