/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.domain

import az.petek.campaign.domain.FlowFailureReason.LOGIN_FAILED
import az.petek.campaign.domain.FlowFailureReason.REGISTRATION_FAILED
import az.petek.campaign.domain.FlowStep.AccountCreated
import az.petek.campaign.domain.FlowStep.AssertIdentity
import az.petek.campaign.domain.FlowStep.Click
import az.petek.campaign.domain.FlowStep.EmailCode
import az.petek.campaign.domain.FlowStep.EmailLink
import az.petek.campaign.domain.FlowStep.Fill
import az.petek.campaign.domain.FlowStep.Goto
import az.petek.campaign.domain.FlowStep.Journey
import az.petek.campaign.domain.FlowStep.PhoneCode
import az.petek.campaign.domain.FlowStep.SaveSession
import az.petek.campaign.domain.FlowStep.Select
import az.petek.campaign.domain.FlowStep.WaitFor

/**
 * The flows of docs/TARGET_CONTRACT.md §2, which the fake target implements: every selector and path is a key of
 * [TargetProfile.DEFAULT_SELECTORS] / [TargetProfile.DEFAULT_PATHS], so a campaign that overrides those keys changes
 * these flows too.
 *
 * After a registration form is submitted the site shows the e-mail code step, the phone code step, the login page or
 * the signed-in home page; the shared [signIn] journey handles whatever comes (a rejected e-mail code is retried once
 * with a newer code, a site asking for the same step a third time is reported) until the user is signed in.
 */
internal object ContractFlows {
    private const val USER_NAME = "session.user_name"
    private const val LOGIN_PAGE = "the login page"

    private val loginForm: List<FlowStep> =
        listOf(
            Goto("login"),
            Fill("login.email", "{self.email}"),
            Fill("login.password", "{self.password}"),
            Click("login.submit"),
        )

    private val signInPages: List<JourneyPage> =
        listOf(
            JourneyPage("the phone code step", "verify.phone_code", listOf(PhoneCode("verify.phone_code", "verify.phone_submit"))),
            JourneyPage("the e-mail code step", "verify.code", listOf(EmailCode("verify.code", "verify.submit"))),
            JourneyPage(
                LOGIN_PAGE,
                "login.email",
                loginForm,
                reason = LOGIN_FAILED,
                stuck = FlowFailure(LOGIN_FAILED, "Login as {self.email} failed", error = "login.error"),
            ),
        )

    /** From whatever the site shows after a registration form until the user is signed in. */
    private val signIn = Journey(label = "signing in", until = USER_NAME, pages = signInPages)

    /** From the login form (submitted without looking at the page first) until the user is signed in. */
    private val signInFromLoginPage = signIn.copy(start = LOGIN_PAGE)

    /** The page after a registration form: the site accepted it once any page of the sign-in journey shows. */
    private fun accepted(form: String): FlowStep =
        WaitFor(
            selectors = listOf(USER_NAME) + signInPages.map { it.selector },
            text = null,
            failure = FlowFailure(REGISTRATION_FAILED, "The $form form was not accepted (still on {url})."),
        )

    private fun formShown(
        selector: String,
        message: String,
    ): FlowStep = WaitFor(listOf(selector), null, failure = FlowFailure(REGISTRATION_FAILED, message))

    private val registerOwner =
        Flow(
            listOf(
                Goto("register"),
                formShown("register.name", "The sign-up page shows no form ({url})."),
                Fill("register.name", "{self.name}"),
                Fill("register.email", "{self.email}"),
                Fill("register.phone", "{self.phone}"),
                Fill("register.password", "{self.password}"),
                Fill("register.company", "{campaign.company}"),
                Click("register.submit"),
                accepted("sign-up"),
                AccountCreated,
                signIn,
                AssertIdentity(USER_NAME),
                SaveSession,
            ),
        )

    private val joinByInvite =
        Flow(
            listOf(
                EmailLink(LinkPurpose.INVITE),
                formShown("invite.name", "The invitation page shows no sign-up form ({url})."),
                Fill("invite.name", "{self.name}"),
                Fill("invite.phone", "{self.phone}"),
                Fill("invite.password", "{self.password}"),
                Click("invite.submit"),
                accepted("invitation"),
                AccountCreated,
                signIn,
                SaveSession,
                AssertIdentity(USER_NAME),
            ),
        )

    private val joinByCode =
        Flow(
            listOf(
                Goto("join"),
                formShown("join.code", "The join page shows no form ({url})."),
                Fill("join.code", "{shared.company_code}"),
                Fill("join.name", "{self.name}"),
                Fill("join.email", "{self.email}"),
                Fill("join.phone", "{self.phone}"),
                Fill("join.password", "{self.password}"),
                Select("join.department", "{self.department}"),
                Click("join.submit"),
                accepted("company code"),
                AccountCreated,
                signIn,
                SaveSession,
                AssertIdentity(USER_NAME),
            ),
        )

    /** A sign-up without a company: name, e-mail and password; a site asking for more overrides the flow. */
    private val signUp =
        Flow(
            listOf(
                Goto("register"),
                formShown("register.email", "The sign-up page shows no form ({url})."),
                Fill("register.name", "{self.name}"),
                Fill("register.email", "{self.email}"),
                Fill("register.password", "{self.password}"),
                Click("register.submit"),
                accepted("sign-up"),
                AccountCreated,
                signIn,
                AssertIdentity(USER_NAME),
                SaveSession,
            ),
        )

    val ALL: Map<String, Flow> =
        linkedMapOf(
            FlowNames.REGISTER_OWNER to registerOwner,
            FlowNames.JOIN_BY_INVITE to joinByInvite,
            FlowNames.JOIN_BY_CODE to joinByCode,
            FlowNames.SIGN_UP to signUp,
            FlowNames.LOGIN to Flow(listOf(signInFromLoginPage, SaveSession)),
            FlowNames.VERIFY_IDENTITY to Flow(listOf(AssertIdentity(USER_NAME))),
        )
}
