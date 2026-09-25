package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.FailureReason
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.AgentTestData.sel
import az.petek.agent.testing.RunFunctionFixture
import az.petek.browser.domain.DialogType
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.StepStatus
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailboxException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.text.Normalizer
import kotlin.time.Duration.Companion.seconds

/** `login`, `verify_identity`, `read_email_code`, `logout` and the standard registry. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRunFunctionsTest {
    private val identity = AgentTestData.itEmployee
    private val fixture = RunFunctionFixture(identity)
    private val password = identity.password.reveal()

    @Test
    fun `the standard registry offers every built-in function`() {
        fixture.registry.names shouldBe RunFunctions.NAMES
        RunFunctions.NAMES shouldBe
            setOf("login", "verify_identity", "read_email_code", "register_owner", "seed_company", "register_and_login", "logout")
    }

    @Test
    fun `login signs in, saves the storage state and records every sub-action`() =
        runTest {
            fixture.site.existingAccount(identity)

            val outcome = fixture.run("login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.stepsTaken shouldBe 6
            fixture.site.signedIn?.email shouldBe identity.email.lowercase()
            fixture.browser.actions shouldContainExactly
                listOf(
                    "navigate /login",
                    "fillSelector ${sel("login.email")}=${identity.email}",
                    "fillSelector ${sel("login.password")}=$password",
                    "clickSelector ${sel("login.submit")}",
                    "saveStorageState ${fixture.runtime.storageStatePath}",
                )
            fixture.actions("login") shouldContainExactly
                listOf(
                    "login: open /login",
                    "login: fill login.email \"${identity.email}\"",
                    "login: fill login.password \"***\"",
                    "login: click login.submit",
                    "login: wait for session.user_name",
                    "login: save storage state",
                )
            val final = fixture.steps.last()
            final.action shouldBe "run login"
            final.status shouldBe StepStatus.PASSED
            final.correlationId shouldBe AgentTestData.CORRELATION_ID
            final.scenarioStep shouldBe "setup-login"
            fixture.artifactsOf(ArtifactType.SCREENSHOT).single().stepId shouldBe final.stepId
            fixture.artifactsOf(ArtifactType.A11Y) shouldHaveSize 0
            fixture.assertPasswordNotRecorded()
        }

    @Test
    fun `a dialog a sub-action made the page open is noted in that sub-action's evidence`() =
        runTest {
            fixture.site.existingAccount(identity)
            val submit = "clickSelector ${sel("login.submit")}"
            fixture.browser.onAction = { action ->
                if (action == submit) fixture.browser.openDialog(DialogType.ALERT, "Xoş gəlmisiniz, ${identity.displayName}")
            }

            val outcome = fixture.run("login")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            val click = fixture.steps.single { it.action == "login: click login.submit" }
            click.detail shouldBe "Browser dialogs (accepted): alert \"Xoş gəlmisiniz, ${identity.displayName}\"."
            fixture.steps.filter { it != click }.forEach { it.detail.orEmpty() shouldNotContain "Browser dialogs" }
        }

    @Test
    fun `each dialog is noted once, by the sub-action that caused it, not again when the function concludes`() =
        runTest {
            fixture.site.existingAccount(identity)
            fixture.browser.onAction = { action ->
                if (action.startsWith("saveStorageState")) fixture.browser.openDialog(DialogType.CONFIRM, "Səhifədən çıxılsın?")
            }

            fixture.run("login")

            fixture.steps.single { it.action == "login: save storage state" }.detail shouldBe
                "Browser dialogs (accepted): confirm \"Səhifədən çıxılsın?\"."
            fixture.steps
                .last()
                .detail
                .orEmpty() shouldNotContain "Browser dialogs"
        }

    @Test
    fun `a refused login fails with the site's error message and saves nothing`() =
        runTest {
            fixture.site.existingAccount(AgentTestData.identity(4, password = "another-password", name = identity.displayName))

            val outcome = fixture.run("login")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.LOGIN_FAILED
            outcome.summary shouldContain "Yanlış e-poçt və ya şifrə"
            fixture.storageStateSaved() shouldBe false
            fixture.steps.last().status shouldBe StepStatus.FAILED
            fixture.artifactsOf(ArtifactType.A11Y).single().stepId shouldBe fixture.steps.last().stepId
        }

    @Test
    fun `verify_identity passes when the page shows this agent's name`() =
        runTest {
            fixture.site.existingAccount(identity)
            fixture.run("login")

            val outcome = fixture.run("verify_identity")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldBe "The session shows 'Cəmil Əliyev'."
        }

    @Test
    fun `verify_identity reports another user's session as an identity mismatch`() =
        runTest {
            fixture.site.existingAccount(identity)
            fixture.site.shownNameOverride = "Əli Kərimov"
            fixture.run("login")

            val outcome = fixture.run("verify_identity")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.IDENTITY_MISMATCH
            outcome.summary shouldBe "The session shows 'Əli Kərimov' but this agent is 'Cəmil Əliyev'."
        }

    @Test
    fun `verify_identity without a session is a login failure`() =
        runTest {
            val outcome = fixture.run("verify_identity")

            outcome.failureReason shouldBe FailureReason.LOGIN_FAILED
            outcome.summary shouldContain "Not signed in"
        }

    @Test
    fun `names are compared trimmed, with collapsed whitespace and in Unicode NFC`() {
        val decomposed = Normalizer.normalize("  Şəhla \n  Çələbiyeva ", Normalizer.Form.NFD)
        TargetFlows.normalizeName(decomposed) shouldBe "Şəhla Çələbiyeva"
    }

    @Test
    fun `read_email_code stores the newest code for the agent`() =
        runTest {
            fixture.verification.sendCode(identity.email, "777111")

            val outcome = fixture.run("read_email_code")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.runtime.variables[AgentVariableKeys.EMAIL_CODE] shouldBe "777111"
            fixture.verification.calls.single() shouldBe (identity.email to MailPurpose.CODE)
        }

    @Test
    fun `read_email_code fails with mail_timeout after a minute without mail`() =
        runTest {
            val outcome = fixture.run("read_email_code")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MAIL_TIMEOUT
            currentTime shouldBe 60_000
            fixture.runtime.variables[AgentVariableKeys.EMAIL_CODE].shouldBeNull()
        }

    @Test
    fun `read_email_code reports an unreachable inbox as mail_unavailable, not as a missing e-mail`() =
        runTest {
            fixture.verification.outage = MailboxException("Mailpit at http://127.0.0.1:8025: search failed (ConnectException)")

            val outcome = fixture.run("read_email_code")

            outcome.status shouldBe ActionStatus.ERROR
            outcome.failureReason shouldBe FailureReason.MAIL_UNAVAILABLE
            outcome.summary shouldBe "Test inbox unreachable: Mailpit at http://127.0.0.1:8025: search failed (ConnectException)"
            currentTime shouldBe 60_000
            val final = fixture.steps.last()
            final.action shouldBe "run read_email_code"
            final.status shouldBe StepStatus.ERROR
            final.detail shouldBe
                "mail_unavailable: Test inbox unreachable: Mailpit at http://127.0.0.1:8025: search failed (ConnectException)"
            fixture.runtime.variables[AgentVariableKeys.EMAIL_CODE].shouldBeNull()
        }

    @Test
    fun `logout signs out and is harmless when already signed out`() =
        runTest {
            fixture.run("logout").summary shouldBe "Already signed out."
            fixture.browser.actions shouldBe emptyList()

            fixture.site.existingAccount(identity)
            fixture.run("login")
            val outcome = fixture.run("logout")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldBe "Signed out."
            fixture.site.signedIn.shouldBeNull()
        }

    @Test
    fun `a run function that exceeds the step timeout fails with timeout`() =
        runTest {
            val outcome = fixture.run("read_email_code", timeout = 20.seconds)

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.TIMEOUT
            currentTime shouldBe 20_000
            fixture.steps.last().action shouldBe "run read_email_code"
        }

    @Test
    fun `a browser failure ends the function with browser_error`() =
        runTest {
            fixture.browser.failOn = { it.startsWith("navigate") }

            val outcome = fixture.run("login")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.BROWSER_ERROR
            outcome.summary shouldContain "scripted failure: navigate /login"
            fixture.steps.first().status shouldBe StepStatus.ERROR
        }
}
