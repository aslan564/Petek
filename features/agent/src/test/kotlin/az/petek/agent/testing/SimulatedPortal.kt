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

package az.petek.agent.testing

import az.petek.browser.domain.BrowserSession
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.TargetProfile
import az.petek.identity.domain.Identity
import az.petek.oracle.domain.TestCompany
import az.petek.oracle.testing.FakeTargetOracle
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny company portal behind a fake browser, following docs/TARGET_CONTRACT.md: sign-up, join by company code, accept an
 * invitation, e-mail code, optional phone code, login, logout and the company page. Pages are represented by the set
 * of visible `data-testid`s of [FakeBrowserSession]; every browser call is still recorded in [browser]'s `actions`.
 * Switches let tests make the site misbehave (reject codes, forget forms, show another user's name).
 */
class SimulatedPortal(
    val verification: FakeVerification,
    val oracle: FakeTargetOracle,
    val browser: FakeBrowserSession = FakeBrowserSession("site"),
) : BrowserSession by browser {
    class Account(
        val email: String,
        val name: String,
        val password: String,
        val phone: String,
        val department: String?,
        var emailVerified: Boolean = false,
        var phoneVerified: Boolean = false,
    )

    val accounts = ConcurrentHashMap<String, Account>()

    /** Last value typed or selected per target profile key, e.g. `join.department`. */
    val fields = ConcurrentHashMap<String, String>()
    val submittedEmailCodes = mutableListOf<String>()
    private val invitations = ConcurrentHashMap<String, String>()

    // Behaviour switches.
    var phoneVerification = false
    var rejectEmailCodes = 0
    var resendCodeOnReject = true
    var landOnLoginAfterVerification = false
    var acceptForms = true
    var registerCompanyInOracle = true
    var shownNameOverride: String? = null
    var rejectPhoneCodes = false
    var publishPhoneCodes = true
    var landOnUnknownPage = false

    /** A broken site: an accepted e-mail code is forgotten, so every login asks for a new one. */
    var forgetEmailVerification = false

    var signedIn: Account? = null
        private set
    private var pendingEmail: String? = null
    private var codeCounter = 100_000
    private var otpCounter = 500_000
    private val keyBySelector = TargetProfile.DEFAULT_SELECTORS.entries.associate { (key, selector) -> selector to key }

    /** Registers an invitation and returns its link (as the test API or the invitation e-mail would). */
    fun invite(identity: Identity): String {
        val token = "tok-${identity.agentId}"
        invitations[token] = identity.email.lowercase()
        return "$BASE/invite/$token"
    }

    /** An existing, verified account (e.g. the owner who signed up earlier). */
    fun existingAccount(identity: Identity): Account =
        Account(
            identity.email.lowercase(),
            identity.displayName,
            identity.password.reveal(),
            identity.phone,
            identity.department,
            true,
            true,
        ).also { accounts[it.email] = it }

    fun latestCode(email: String): String? = codes[email.lowercase()]

    private val codes = ConcurrentHashMap<String, String>()

    override suspend fun navigate(pathOrUrl: String) {
        browser.navigate(pathOrUrl)
        val path = URI(pathOrUrl).path ?: pathOrUrl
        when {
            path == "/login" -> {
                showLogin()
            }

            path == "/register" -> {
                show(pathOrUrl, *REGISTER_FORM)
            }

            path == "/join" -> {
                show(pathOrUrl, *JOIN_FORM)
            }

            path.startsWith("/invite/") -> {
                if (invitations.containsKey(path.removePrefix("/invite/"))) {
                    show(pathOrUrl, *INVITE_FORM)
                } else {
                    show(pathOrUrl)
                }
            }

            path == "/company" -> {
                showCompany()
            }

            else -> {
                signedIn?.let { showHome(it, pathOrUrl) } ?: showLogin()
            }
        }
    }

    override suspend fun fillSelector(
        selector: String,
        text: String,
    ) {
        browser.fillSelector(selector, text)
        fields[key(selector)] = text
    }

    override suspend fun selectSelector(
        selector: String,
        option: String,
    ) {
        browser.selectSelector(selector, option)
        fields[key(selector)] = option
    }

    override suspend fun clickSelector(selector: String) {
        browser.clickSelector(selector)
        when (key(selector)) {
            "login.submit" -> submitLogin()
            "register.submit" -> submitRegistration()
            "join.submit" -> submitJoin()
            "invite.submit" -> submitInvitation()
            "verify.submit" -> submitEmailCode()
            "verify.phone_submit" -> submitPhoneCode()
            "session.logout" -> logout()
        }
    }

    private fun submitRegistration() {
        if (!acceptForms) return show(browser.url, *REGISTER_FORM)
        val account = create(field("register.email"), field("register.name"), field("register.password"), field("register.phone"), null)
        if (registerCompanyInOracle) {
            oracle.companies[COMPANY_ID] = TestCompany(COMPANY_ID, field("register.company"), COMPANY_CODE, isTest = true)
            oracle.owners[account.email] = COMPANY_ID
        }
        startEmailVerification(account)
    }

    private fun submitJoin() {
        if (!acceptForms || field("join.code") != COMPANY_CODE) return show(browser.url, *JOIN_FORM)
        val account =
            create(field("join.email"), field("join.name"), field("join.password"), field("join.phone"), fields["join.department"])
        startEmailVerification(account)
    }

    private fun submitInvitation() {
        val token = URI(browser.url).path.removePrefix("/invite/")
        val email = invitations[token]
        if (!acceptForms || email == null) return show(browser.url, *INVITE_FORM)
        invitations.remove(token)
        startEmailVerification(create(email, field("invite.name"), field("invite.password"), field("invite.phone"), null))
    }

    private fun submitEmailCode() {
        val account = pendingEmail?.let { accounts[it] } ?: return showLogin()
        val code = field("verify.code")
        submittedEmailCodes += code
        if (rejectEmailCodes > 0) {
            rejectEmailCodes--
            if (resendCodeOnReject) sendCode(account)
            return showEmailCodeStep(account)
        }
        if (code != codes[account.email]) return showEmailCodeStep(account)
        account.emailVerified = !forgetEmailVerification
        continueAfterEmail(account)
    }

    private fun submitPhoneCode() {
        val account = pendingEmail?.let { accounts[it] } ?: return showLogin()
        if (rejectPhoneCodes || field("verify.phone_code") != oracle.otps[account.phone]) return show("/verify/phone", *PHONE_FORM)
        account.phoneVerified = true
        land(account)
    }

    private fun submitLogin() {
        val account = accounts[field("login.email").lowercase()]
        if (account == null || account.password != field("login.password")) {
            showLogin()
            browser.visibleSelectors += sel("login.error")
            browser.selectorTexts[sel("login.error")] = "Yanlış e-poçt və ya şifrə"
            return
        }
        when {
            !account.emailVerified -> startEmailVerification(account)
            phoneVerification && !account.phoneVerified -> askPhoneCode(account)
            else -> signIn(account)
        }
    }

    private fun logout() {
        signedIn = null
        showLogin()
    }

    private fun create(
        email: String,
        name: String,
        password: String,
        phone: String,
        department: String?,
    ): Account = Account(email.lowercase(), name, password, phone, department).also { accounts[it.email] = it }

    private fun startEmailVerification(account: Account) {
        sendCode(account)
        showEmailCodeStep(account)
    }

    private fun sendCode(account: Account) {
        val code = (++codeCounter).toString()
        codes[account.email] = code
        verification.sendCode(account.email, code)
    }

    private fun continueAfterEmail(account: Account) {
        if (phoneVerification && !account.phoneVerified) askPhoneCode(account) else land(account)
    }

    private fun askPhoneCode(account: Account) {
        pendingEmail = account.email
        if (publishPhoneCodes) oracle.otps[account.phone] = (++otpCounter).toString()
        show("/verify/phone", *PHONE_FORM)
    }

    private fun land(account: Account) {
        pendingEmail = null
        when {
            landOnUnknownPage -> show("/welcome")
            landOnLoginAfterVerification -> showLogin()
            else -> signIn(account)
        }
    }

    private fun signIn(account: Account) {
        pendingEmail = null
        signedIn = account
        showHome(account, "/")
    }

    private fun showEmailCodeStep(account: Account) {
        pendingEmail = account.email
        show("/verify?email=${account.email}", *VERIFY_FORM)
    }

    private fun showLogin() = show("/login", *LOGIN_FORM)

    private fun showHome(
        account: Account,
        url: String,
    ) {
        show(url, *SESSION)
        browser.selectorTexts[sel("session.user_name")] = shownNameOverride ?: "  ${account.name} "
    }

    private fun showCompany() {
        val account = signedIn ?: return showLogin()
        showHome(account, "/company")
        browser.visibleSelectors += sel("company.code")
        browser.selectorTexts[sel("company.code")] = " $COMPANY_CODE "
    }

    private fun show(
        url: String,
        vararg keys: String,
    ) {
        browser.url = url
        browser.visibleSelectors.clear()
        keys.forEach { browser.visibleSelectors += sel(it) }
    }

    private fun field(key: String): String = fields[key].orEmpty()

    private fun key(selector: String): String = keyBySelector[selector] ?: selector

    private fun sel(key: String): String = AgentTestData.sel(key)

    companion object {
        const val BASE = "https://staging.portal.test"
        const val COMPANY_ID = "c1"
        const val COMPANY_CODE = "PTK-4821"

        private val LOGIN_FORM = arrayOf("login.email", "login.password", "login.submit")
        private val REGISTER_FORM =
            arrayOf("register.name", "register.email", "register.phone", "register.password", "register.company", "register.submit")
        private val JOIN_FORM =
            arrayOf("join.code", "join.name", "join.email", "join.phone", "join.password", "join.department", "join.submit")
        private val INVITE_FORM = arrayOf("invite.name", "invite.phone", "invite.password", "invite.submit")
        private val VERIFY_FORM = arrayOf("verify.code", "verify.submit")
        private val PHONE_FORM = arrayOf("verify.phone_code", "verify.phone_submit")
        private val SESSION = arrayOf("session.user_name", "session.user_role", "session.logout")
    }
}
