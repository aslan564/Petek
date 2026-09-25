/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.domain

import az.petek.campaign.testing.KNOWN_RUN_FUNCTIONS
import az.petek.campaign.testing.campaign
import az.petek.campaign.testing.settings
import az.petek.campaign.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The flow, overlay, API prefix and pacing rules of the campaign validator ([TargetProfileRules]). */
class TargetProfileRulesTest {
    private val validator = DefaultCampaignValidator()

    private fun issues(
        target: TargetProfile,
        pacing: Pacing = Pacing.NONE,
        sourceLines: SourceLines = SourceLines.NONE,
    ): List<ValidationIssue> =
        validator.validate(
            campaign(step("s1"), settings = settings().copy(pacing = pacing), target = target, sourceLines = sourceLines),
            KNOWN_RUN_FUNCTIONS,
        )

    private fun messages(target: TargetProfile): List<String> = issues(target).map { it.message }

    private fun withFlow(
        name: String,
        vararg steps: FlowStep,
        selectors: Map<String, String> = emptyMap(),
    ): TargetProfile =
        TargetProfile.DEFAULT.copy(
            selectors = selectors,
            flows =
                TargetProfile.DEFAULT_FLOWS + (name to Flow(steps.toList())),
        )

    private fun single(
        target: TargetProfile,
        fragment: String,
    ): String {
        val all = messages(target)
        val matching = all.filter { fragment in it }
        check(matching.size == 1) { "expected one issue containing '$fragment', got:\n${all.joinToString("\n")}" }
        return matching.single()
    }

    @Test
    fun `the contract flows and a plain profile are valid`() {
        messages(TargetProfile.DEFAULT).shouldBeEmpty()
    }

    @Test
    fun `a realistic custom login flow is valid`() {
        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.Goto("login"),
                FlowStep.Fill("login.email", "{self.email}"),
                FlowStep.Fill("login.password", "{self.password}"),
                FlowStep.Fill("login.company_code", "{shared.company_code}"),
                FlowStep.Click("role=button[name=\"Daxil ol\"]"),
                FlowStep.WaitFor(
                    listOf("session.user_name"),
                    null,
                    30.seconds,
                    FlowFailure(FlowFailureReason.LOGIN_FAILED, "Login as {self.email} failed on {url}", "role=alert"),
                ),
                FlowStep.EmailLink(LinkPurpose.VERIFY, "registration/verify\\?token=", into = ValueTarget.vars("verify")),
                FlowStep.Goto("{vars.verify}"),
                FlowStep.Read("#code", ValueTarget.shared("company_code"), "([A-Z0-9]{8})"),
                FlowStep.SetShared("greeting", "{self.first_name} {self.last_name} @ {campaign.company}"),
                FlowStep.SaveSession,
                selectors = mapOf("login.company_code" to "input[autocomplete=organization]"),
            )

        messages(target).shouldBeEmpty()
    }

    @Test
    fun `flows have known names and steps`() {
        single(withFlow("logn", FlowStep.SaveSession), "unknown flow 'logn'") shouldContain FlowNames.ALL.joinToString(", ")
        single(withFlow(FlowNames.LOGIN), "flow 'login' has no steps")
    }

    @Test
    fun `verify_identity must assert the identity somewhere`() {
        single(withFlow(FlowNames.VERIFY_IDENTITY, FlowStep.SaveSession), "must contain an assert_identity step")
        messages(
            withFlow(
                FlowNames.VERIFY_IDENTITY,
                FlowStep.IfVisible("#menu", listOf(FlowStep.AssertIdentity("session.user_name"))),
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `the password may only be typed into a field`() {
        messages(withFlow(FlowNames.LOGIN, FlowStep.Fill("login.password", "{self.password}"))).shouldBeEmpty()

        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.Goto("/login?p={self.password}"),
                FlowStep.Select("#x", "{self.password}"),
                FlowStep.SetShared("leak", "{self.password}"),
                FlowStep.WaitFor(emptyList(), "{self.password}"),
                FlowStep.WaitFor(listOf("#a"), null, failure = FlowFailure(message = "wrong {self.password}")),
                FlowStep.Click("text={self.password}"),
            )

        messages(target).count { "{self.password} may only be typed into a field" in it } shouldBe 6
    }

    @Test
    fun `only flow placeholders are allowed, and url only in failure messages`() {
        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.Fill("login.email", "{self.nickname}"),
                FlowStep.Fill("login.email", "{last_id}"),
                FlowStep.Fill("login.email", "{shared.Bad-Key}"),
                FlowStep.Fill("login.email", "{url}"),
                FlowStep.Fill("login.email", "{Self.Email}"),
            )

        val all = messages(target)

        all.single { "{self.nickname}" in it } shouldContain "self fields: email, name"
        all.single { "{last_id}" in it } shouldContain "flows may use {self.<field>}"
        all.single { "'{shared.Bad-Key}' looks like a placeholder" in it }
        all.single { "{url} is only available in failure messages" in it }
        all.single { "'{Self.Email}' looks like a placeholder" in it }
    }

    @Test
    fun `selector references must exist when they are shaped like a key of a known group`() {
        single(withFlow(FlowNames.LOGIN, FlowStep.Click("login.emial")), "'login.emial' looks like a selector key") shouldContain
            "login.email, login.error, login.password, login.submit"
        messages(withFlow(FlowNames.LOGIN, FlowStep.Click("button.primary"), FlowStep.Click("p.font-medium"))).shouldBeEmpty()
        single(withFlow(FlowNames.LOGIN, FlowStep.Click(" ")), "selector must not be blank")
    }

    @Test
    fun `a campaign's own selector keys count as keys`() {
        val target = withFlow(FlowNames.LOGIN, FlowStep.Click("login.sso"), selectors = mapOf("login.sso" to "#sso"))

        messages(target).shouldBeEmpty()
    }

    @Test
    fun `goto takes path keys, target paths and templates but never another host`() {
        messages(withFlow(FlowNames.LOGIN, FlowStep.Goto("login"), FlowStep.Goto("/x"), FlowStep.Goto("{vars.link}"))).shouldBeEmpty()

        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.Goto("https://evil.example/login"),
                FlowStep.Goto("//evil.example"),
                FlowStep.Goto("relative/path"),
                FlowStep.Goto(" "),
            )

        val all = messages(target)
        all.count { "must be a path key, a path on the target" in it } shouldBe 3
        all.count { "must not be blank" in it } shouldBe 1
    }

    @Test
    fun `regular expressions must compile`() {
        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.ExpectUrl("(unclosed"),
                FlowStep.EmailLink(LinkPurpose.ANY, "[bad"),
                FlowStep.Read("#a", ValueTarget.vars("x"), "*nothing"),
            )

        messages(target).count { "is not a valid regular expression" in it } shouldBe 3
    }

    @Test
    fun `timeouts must be positive and finite`() {
        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.WaitFor(listOf("#a"), null, Duration.ZERO),
                FlowStep.ExpectUrl("/x", (-1).seconds),
                FlowStep.IfVisible("#a", listOf(FlowStep.Click("#b")), Duration.INFINITE),
            )

        messages(target).count { "timeout_s must be positive and finite" in it } shouldBe 3
    }

    @Test
    fun `wait_for needs exactly one thing to wait for and if_visible needs steps`() {
        single(withFlow(FlowNames.LOGIN, FlowStep.WaitFor(listOf("#a"), "text")), "needs exactly one of selector, any or text")
        single(withFlow(FlowNames.LOGIN, FlowStep.WaitFor(emptyList(), null)), "needs exactly one of selector, any or text")
        single(withFlow(FlowNames.LOGIN, FlowStep.WaitFor(emptyList(), " ")), "text must not be blank")
        single(withFlow(FlowNames.LOGIN, FlowStep.IfVisible("#a", emptyList())), "needs at least one step under then")
    }

    @Test
    fun `journeys need labelled pages, a known start and at least one visit`() {
        val page = JourneyPage("the form", "#form", listOf(FlowStep.Click("#go")))
        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.Journey(
                    " ",
                    "#done",
                    listOf(page, page, page.copy(steps = emptyList(), label = "x")),
                    start = "nowhere",
                    maxVisits = 0,
                ),
                FlowStep.Journey("empty", "#done", emptyList()),
            )

        val all = messages(target)

        all.single { "label must not be blank" in it }
        all.single { "label 'the form' is used twice" in it }
        all.single { "pages[2] needs at least one step" in it }
        all.single { "start 'nowhere' is not the label of one of its pages" in it }
        all.single { "max_visits must be at least 1, was 0" in it }
        all.single { "needs at least one page" in it }
    }

    @Test
    fun `steps nested in journeys and conditions are checked too`() {
        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.Journey(
                    "signing in",
                    "session.user_name",
                    listOf(
                        JourneyPage(
                            "the login page",
                            "login.email",
                            listOf(FlowStep.IfVisible("#x", listOf(FlowStep.Goto("https://elsewhere")))),
                            stuck = FlowFailure(message = "{nope}"),
                        ),
                    ),
                ),
            )

        val all = issues(target)

        all.map { it.message }.single { "target_profile.flows.login[0].journey.pages[0].steps[0].if_visible.then[0].goto" in it }
        all.map { it.message }.single { "unknown placeholder {nope}" in it } shouldContain "pages[0].stuck"
    }

    @Test
    fun `the shared invitation link cannot be overwritten`() {
        val target =
            withFlow(
                FlowNames.LOGIN,
                FlowStep.SetShared("invite_link", "x"),
                FlowStep.Read("#a", ValueTarget.shared("invite_link")),
                FlowStep.EmailLink(LinkPurpose.ANY, into = ValueTarget.shared("invite_link")),
            )

        messages(target).count { "cannot store into shared.invite_link" in it } shouldBe 3
    }

    @Test
    fun `shared keys use the value key alphabet`() {
        single(withFlow(FlowNames.LOGIN, FlowStep.SetShared("Company-Code", "x")), "must use only lower-case letters")
    }

    @Test
    fun `the API prefix is empty or a clean path`() {
        listOf("", "/api", "/api/v1", "/rest/v2.1").forEach { prefix ->
            messages(TargetProfile.DEFAULT.copy(apiPrefix = prefix)).shouldBeEmpty()
        }
        listOf("api", "/api/", "//api", "/api?x=1", "/a pi", "https://x/api").forEach { prefix ->
            single(TargetProfile.DEFAULT.copy(apiPrefix = prefix), "api_prefix must be empty or a path like /api/v1")
        }
    }

    @Test
    fun `local storage keys and dismiss selectors must not be blank`() {
        val target = TargetProfile.DEFAULT.copy(localStorage = mapOf(" " to "1"), dismiss = listOf("#ok", ""))

        val all = messages(target)

        all.single { "local_storage has a blank key" in it }
        all.single { "target_profile.dismiss[1]: selector must not be blank" in it }
    }

    @Test
    fun `dismiss selectors are used as written, so a placeholder in one is reported`() {
        val target = TargetProfile.DEFAULT.copy(dismiss = listOf("role=button[name=\"Qəbul edirəm\"]", "#hi-{self.agent_id}"))

        single(target, "target_profile.dismiss[1]: overlay selectors are checked before every flow step as written")
        messages(TargetProfile.DEFAULT.copy(dismiss = listOf("role=button[name=\"Qəbul edirəm\"]", "session.logout"))).shouldBeEmpty()
    }

    @Test
    fun `pacing needs a non-negative stagger and at least one parallel actor`() {
        issues(TargetProfile.DEFAULT, Pacing(1500.milliseconds, 3)).shouldBeEmpty()
        issues(TargetProfile.DEFAULT, Pacing((-1).milliseconds, 0)) shouldHaveSize 2
        issues(TargetProfile.DEFAULT, Pacing(Duration.INFINITE)).single().message shouldContain "start_stagger_ms"
    }

    @Test
    fun `issues point at the flow step's line`() {
        val lines = SourceLines(mapOf("target_profile.flows.login" to 30, "target_profile.flows.login[1]" to 32))
        val target = withFlow(FlowNames.LOGIN, FlowStep.SaveSession, FlowStep.Goto("https://x"))

        issues(target, sourceLines = lines).single().line shouldBe 32
    }

    @Test
    fun `a leftover api placeholder in a campaign built in code is explained`() {
        val campaign =
            campaign(
                step(
                    "s1",
                    assertions = listOf(AssertionSpec.HttpStatus("{api}/x", "GET", 200)),
                ),
            )

        val all = validator.validate(campaign, KNOWN_RUN_FUNCTIONS).map { it.message }

        all.any { "{api} stands for target_profile.api_prefix" in it } shouldBe true
    }
}
