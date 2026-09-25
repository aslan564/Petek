package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.AgentTestData.sel
import az.petek.agent.testing.RunFunctionFixture
import az.petek.agent.testing.SimulatedKadro
import az.petek.evidence.domain.StepStatus
import az.petek.mail.domain.MailPurpose
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterAndLoginRunFunctionTest {
    private val invited = AgentTestData.itManager
    private val joiner = AgentTestData.hrManager

    private fun RunFunctionFixture.attempts() =
        actions("register_and_login").filter {
            it.startsWith("register_and_login: attempt ") &&
                "of" in it
        }

    private fun RunFunctionFixture.clicks(key: String) = browser.actions.count { it == "clickSelector ${sel(key)}" }

    @Test
    fun `an invited tester follows the published link, verifies the e-mail and signs in`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), fixture.site.invite(invited))

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldBe "Joined by invitation and signed in. The session shows 'Vəli Həsənov'."
            val account = fixture.site.accounts.getValue(invited.email.lowercase())
            account.name shouldBe invited.displayName
            account.password shouldBe invited.password.reveal()
            account.phone shouldBe invited.phone
            account.emailVerified shouldBe true
            fixture.site.signedIn shouldBe account
            fixture.storageStateSaved() shouldBe true
            fixture.verification.calls shouldContainExactly listOf(invited.email to MailPurpose.CODE)
            fixture.attempts() shouldHaveSize 1
            fixture.assertPasswordNotRecorded()
        }

    @Test
    fun `without a published link the invitation e-mail is used`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.verification.sendLink(invited.email, fixture.site.invite(invited))

            fixture.run("register_and_login").status shouldBe ActionStatus.SUCCEEDED

            fixture.verification.calls.first() shouldBe (invited.email to MailPurpose.LINK)
        }

    @Test
    fun `a company-code tester waits for the code and joins their department`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)
            launch {
                delay(2.minutes)
                fixture.shared.put(SharedRunState.COMPANY_CODE, SimulatedKadro.COMPANY_CODE)
            }

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldStartWith "Joined by company code and signed in."
            currentTime.toInt() shouldBeGreaterThanOrEqual 120_000
            fixture.site.fields["join.code"] shouldBe SimulatedKadro.COMPANY_CODE
            fixture.site.fields["join.email"] shouldBe joiner.email
            fixture.site.accounts
                .getValue(joiner.email.lowercase())
                .department shouldBe "HR"
            fixture.browser.actions shouldContain "selectSelector ${sel("join.department")}=HR"
        }

    @Test
    fun `a company code that never appears is a missing prerequisite and is not retried`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldContain "No company code was published within 5m"
            currentTime shouldBe 300_000
            fixture.attempts() shouldHaveSize 1
            fixture.steps.single { it.action == "register_and_login: wait for the company code" }.status shouldBe StepStatus.FAILED
        }

    @Test
    fun `phone verification and a landing on the login page are handled on the way in`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)
            fixture.shared.put(SharedRunState.COMPANY_CODE, SimulatedKadro.COMPANY_CODE)
            fixture.site.phoneVerification = true
            fixture.site.landOnLoginAfterVerification = true

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.site.accounts
                .getValue(joiner.email.lowercase())
                .phoneVerified shouldBe true
            fixture.clicks("verify.phone_submit") shouldBe 1
            fixture.clicks("login.submit") shouldBe 1
        }

    @Test
    fun `a rejected e-mail code is retried once with a newer code`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), fixture.site.invite(invited))
            fixture.site.rejectEmailCodes = 1

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            val (first, second) = fixture.site.submittedEmailCodes
            second shouldNotBe first
            second shouldBe fixture.site.latestCode(invited.email)
            fixture.attempts() shouldHaveSize 1
            fixture.steps.map { it.action } shouldContain "register_and_login: e-mail code rejected; waiting once for a newer code"
        }

    @Test
    fun `a code rejected twice fails the attempt, and three such attempts fail the tester with otp_rejected`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), fixture.site.invite(invited))
            fixture.site.rejectEmailCodes = Int.MAX_VALUE

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.OTP_REJECTED
            outcome.summary shouldBe
                "Registration failed after 3 attempts; last: The e-mail code was rejected twice (/verify?email=${invited.email})."
            fixture.attempts() shouldContainExactly
                listOf(
                    "register_and_login: attempt 1 of 3",
                    "register_and_login: attempt 2 of 3 (account exists: signing in)",
                    "register_and_login: attempt 3 of 3 (account exists: signing in)",
                )
            fixture.site.submittedEmailCodes shouldHaveSize 6
            fixture.clicks("invite.submit") shouldBe 1
            fixture.clicks("login.submit") shouldBe 2
            fixture.steps.last().status shouldBe StepStatus.FAILED
        }

    @Test
    fun `a rejected code without a newer one is healed by signing in again on the next attempt`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), fixture.site.invite(invited))
            fixture.site.rejectEmailCodes = 1
            fixture.site.resendCodeOnReject = false

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.attempts() shouldHaveSize 2
            val failure = fixture.steps.single { it.action == "register_and_login: attempt 1 failed" }
            failure.detail!! shouldContain "no newer code arrived within 1m"
        }

    @Test
    fun `a retry that verifies the e-mail and lands on the login page again signs in within the same attempt`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), fixture.site.invite(invited))
            fixture.site.rejectEmailCodes = 1
            fixture.site.resendCodeOnReject = false
            fixture.site.landOnLoginAfterVerification = true

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.attempts() shouldHaveSize 2
            fixture.clicks("login.submit") shouldBe 2
            fixture.site.signedIn?.email shouldBe invited.email.lowercase()
        }

    @Test
    fun `a site that asks for the e-mail code after every login is stopped instead of looping`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), fixture.site.invite(invited))
            fixture.site.forgetEmailVerification = true
            fixture.site.landOnLoginAfterVerification = true

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.LOGIN_FAILED
            outcome.summary shouldContain "last: The site asked for the login page 3 times"
            fixture.attempts() shouldHaveSize 3
            fixture.site.submittedEmailCodes shouldHaveSize 6
            fixture.clicks("login.submit") shouldBe 6
            fixture.steps
                .single { it.action == "register_and_login: attempt 1 failed" }
                .detail!! shouldContain "The site asked for the e-mail code step 3 times"
        }

    @Test
    fun `a form that is never accepted fails after exactly three attempts`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)
            fixture.shared.put(SharedRunState.COMPANY_CODE, SimulatedKadro.COMPANY_CODE)
            fixture.site.acceptForms = false

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.REGISTRATION_FAILED
            outcome.summary shouldContain "Registration failed after 3 attempts; last: The company code form was not accepted"
            fixture.clicks("join.submit") shouldBe 3
            fixture.storageStateSaved() shouldBe false
        }

    @Test
    fun `a transient browser failure is healed by the next attempt`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)
            fixture.shared.put(SharedRunState.COMPANY_CODE, SimulatedKadro.COMPANY_CODE)
            var failures = 0
            fixture.browser.failOn = { action -> action == "clickSelector ${sel("join.submit")}" && failures++ == 0 }

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.attempts() shouldHaveSize 2
            fixture.steps.single { it.action == "register_and_login: attempt 1 failed" }.detail!! shouldContain "Browser error"
        }

    @Test
    fun `an identity mismatch is a finding and is not hidden by a retry`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), fixture.site.invite(invited))
            fixture.site.shownNameOverride = AgentTestData.admin.displayName

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.IDENTITY_MISMATCH
            fixture.attempts() shouldHaveSize 1
        }

    @Test
    fun `the owner cannot use the join flow`() =
        runTest {
            val fixture = RunFunctionFixture(AgentTestData.admin)

            val outcome = fixture.run("register_and_login")

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            fixture.browser.actions shouldBe emptyList()
        }

    @Test
    fun `the step timeout bounds all attempts together`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)

            val outcome = fixture.run("register_and_login", timeout = 45.seconds)

            outcome.failureReason shouldBe FailureReason.TIMEOUT
            currentTime shouldBe 45_000
        }

    @Test
    fun `a rejected phone code fails with otp_rejected after every attempt`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)
            fixture.shared.put(SharedRunState.COMPANY_CODE, SimulatedKadro.COMPANY_CODE)
            fixture.site.phoneVerification = true
            fixture.site.rejectPhoneCodes = true

            val outcome = fixture.run("register_and_login")

            outcome.failureReason shouldBe FailureReason.OTP_REJECTED
            outcome.summary shouldContain "The phone code for ${joiner.phone} was rejected."
            fixture.clicks("verify.phone_submit") shouldBe 3
            fixture.attempts() shouldHaveSize 3
        }

    @Test
    fun `a phone step without a code in the test API is a registration failure`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)
            fixture.shared.put(SharedRunState.COMPANY_CODE, SimulatedKadro.COMPANY_CODE)
            fixture.site.phoneVerification = true
            fixture.site.publishPhoneCodes = false

            val outcome = fixture.run("register_and_login")

            outcome.failureReason shouldBe FailureReason.REGISTRATION_FAILED
            outcome.summary shouldContain "The test API has no phone code for ${joiner.phone}."
            fixture.clicks("verify.phone_submit") shouldBe 0
        }

    @Test
    fun `an unrecognised page after verification fails the attempt and the next attempt signs in`() =
        runTest {
            val fixture = RunFunctionFixture(joiner)
            fixture.shared.put(SharedRunState.COMPANY_CODE, SimulatedKadro.COMPANY_CODE)
            fixture.site.landOnUnknownPage = true

            val outcome = fixture.run("register_and_login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.attempts() shouldHaveSize 2
            fixture.steps.single { it.action == "register_and_login: attempt 1 failed" }.detail shouldBe
                "Unexpected page while signing in: /welcome."
            fixture.clicks("join.submit") shouldBe 1
        }

    @Test
    fun `an expired invitation link is a registration failure`() =
        runTest {
            val fixture = RunFunctionFixture(invited)
            fixture.shared.put(SharedRunState.inviteLink(invited.email), "${SimulatedKadro.BASE}/invite/expired")

            val outcome = fixture.run("register_and_login")

            outcome.failureReason shouldBe FailureReason.REGISTRATION_FAILED
            outcome.summary shouldContain "The invitation page shows no sign-up form"
            fixture.browser.actions shouldNotContain "clickSelector ${sel("invite.submit")}"
            fixture.attempts() shouldHaveSize 3
        }
}
