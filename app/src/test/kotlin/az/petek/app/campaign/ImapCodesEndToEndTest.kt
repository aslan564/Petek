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

package az.petek.app.campaign

import az.petek.app.config.ConfigLoader
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.testing.CliHarness.Companion.done
import az.petek.app.testing.scriptedLlm
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import az.petek.faketarget.mail.SentMail
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.infrastructure.NoOpMonitorView
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties

/**
 * Faza 16's ready-when (docs/PLAN.md): on the fake target, the sign-up codes and the invitation are read from the
 * owner's IMAP inbox on a real mail server (GreenMail, test only; the owner's decision of 2026-09-30), and no tester
 * sees another's mail. The site's mail goes over SMTP to the owner's box, each addressed to the tester's `+` address,
 * as a catch-all box receives it; Pətək reads it with `PETEK_MAIL_SOURCE=imap`, Mailpit is not there at all. Real
 * Chromium, production wiring.
 */
@Tag("e2e")
class ImapCodesEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private val mail =
        GreenMail(arrayOf(ServerSetup(0, LOOPBACK, ServerSetup.PROTOCOL_SMTP), ServerSetup(0, LOOPBACK, ServerSetup.PROTOCOL_IMAP)))
            .apply {
                start()
                setUser(INBOX, INBOX, PASSWORD)
            }
    private val site = FakeTargetServer(FakeTargetConfig(testMailDomain = DOMAIN)).apply { outbox.onSent(::relay) }.start()

    @AfterEach
    fun stop() {
        site.close()
        mail.stop()
    }

    @Test
    fun `sign-up codes and the invitation are read from the owner's IMAP inbox, each tester only its own`() =
        runBlocking<Unit> {
            val env =
                dir.resolve("imap.env").also {
                    Files.writeString(
                        it,
                        """
                        PETEK_TARGET=${site.baseUrl}
                        PETEK_PRODUCTION_HOSTS=
                        PETEK_TEST_TOKEN=dev-token
                        PETEK_MAIL_SOURCE=imap
                        PETEK_MAIL_INBOX=$INBOX
                        PETEK_IMAP_HOST=$LOOPBACK
                        PETEK_IMAP_PORT=${mail.imap.port}
                        PETEK_IMAP_PASSWORD=$PASSWORD
                        PETEK_IMAP_TLS=false
                        PETEK_MAILPIT_URL=http://127.0.0.1:9
                        PETEK_IDENTITY_SECRET=imap-codes-e2e-secret
                        PETEK_BROWSER_HEADLESS=true
                        PETEK_EVIDENCE_DIR=evidence
                        """.trimIndent() + "\n",
                    )
                }
            val config = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the test names its secret") }).load(env)
            val file = dir.resolve("imap.yaml").also { Files.writeString(it, CAMPAIGN) }

            AppContainer(config, AppOverrides(llm = scriptedLlm { done() }, monitor = NoOpMonitorView)).use { container ->
                val campaign = container.campaigns.execute(file, container.knownRunFunctions)
                val runner = container.campaignRunner(headless = true)
                // Started just after a second begins, the owner's code (well under a second later on a warm machine)
                // arrives within that same second, which the server keeps as the whole second: still found.
                while (Instant.now().nano / NANOS_PER_MILLI !in START_WINDOW_MS) Thread.sleep(1)
                val summary = runner.run(campaign, RunOptions())

                val testers = container.identities.findByRun(summary.runId)
                withClue("$summary\n${testers.map { "${it.agentId} ${it.status}" }}") {
                    summary.outcome shouldBe RunOutcome.PASSED
                    testers.map { it.status }.toSet() shouldBe setOf(IdentityStatus.ACTIVE)
                }
                // Every tester has its own `+` address of the owner's box, and its own mail reached that box only so.
                val addresses = testers.map { it.email }
                addresses.toSet() shouldHaveSize 3
                addresses.forEach { it shouldMatch Regex("owner\\+[a-z0-9]+-a\\d{2}@company\\.test") }
                val delivered = mail.receivedMessages.flatMap { message -> message.allRecipients.map { it.toString() } }
                delivered.toSet() shouldContainAll addresses
                // The site's own Mailpit API was never there to ask: every code came over IMAP.
                mail.receivedMessages.size shouldBe site.outbox.messages().size
            }
        }

    /** The site's mail, sent over SMTP to the owner's box with the tester's address in its headers (a catch-all box). */
    private fun relay(sent: SentMail) {
        val session = Session.getInstance(Properties())
        val message =
            MimeMessage(session).apply {
                setFrom(InternetAddress(sent.from.address, sent.from.name, Charsets.UTF_8.name()))
                setRecipients(Message.RecipientType.TO, sent.to.map { InternetAddress(it.address) }.toTypedArray())
                setSubject(sent.subject, Charsets.UTF_8.name())
                val body =
                    MimeMultipart("alternative").apply {
                        addBodyPart(MimeBodyPart().apply { setText(sent.text, Charsets.UTF_8.name()) })
                        if (sent.html.isNotBlank()) addBodyPart(MimeBodyPart().apply { setContent(sent.html, "text/html; charset=UTF-8") })
                    }
                setContent(body)
            }
        session.getTransport("smtp").use { transport ->
            transport.connect(LOOPBACK, mail.smtp.port, null, null)
            transport.sendMessage(message, arrayOf(InternetAddress(INBOX)))
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DOMAIN = "company.test"
        const val INBOX = "owner@$DOMAIN"
        const val PASSWORD = "imap-password-123"
        const val NANOS_PER_MILLI = 1_000_000
        val START_WINDOW_MS = 20..60

        /** An owner who signs up and seeds the company, a manager by invitation and an employee by the company code. */
        val CAMPAIGN =
            """
            campaign:
              name: imap-codes
              testers: 3
              seed: 21
              roles: {admin: 1, manager: 1, employee: 1}
              departments: [IT]
              registration: {invite: 1, company_code: 1}
              budget: {max_steps_per_agent: 5, max_minutes: 4}
            setup:
              - id: owner_signup
                actor: admin
                run: register_owner
              - id: seed
                actor: admin
                run: seed_company
              - id: join
                actor: employee[*] | manager[*]
                run: register_and_login
            steps:
              - id: who-am-i
                actor: [admin, manager[*], employee[*]]
                run: verify_identity
            """.trimIndent() + "\n"
    }
}
