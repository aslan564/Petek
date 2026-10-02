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

package az.petek.mail.infrastructure

import az.petek.core.security.Secret
import az.petek.mail.application.DefaultAwaitVerificationUseCase
import az.petek.mail.domain.DefaultVerificationExtractor
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailboxException
import az.petek.mail.domain.UnreadableMailException
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Properties
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [ImapMailbox] against a real IMAP server (GreenMail, test only; the owner's decision of 2026-09-30): the owner's
 * catch-all box receives every tester's mail over SMTP, each addressed to the tester's `+` address, and each tester
 * reads only its own. Complements [ImapMailboxTest], which covers the matching rules without a server.
 */
class ImapMailboxServerTest {
    private val server =
        GreenMail(arrayOf(ServerSetup(0, LOOPBACK, ServerSetup.PROTOCOL_SMTP), ServerSetup(0, LOOPBACK, ServerSetup.PROTOCOL_IMAP)))
            .apply {
                start()
                setUser(INBOX, INBOX, PASSWORD)
            }
    private val since = Instant.now().minus(1, ChronoUnit.MINUTES)
    private val mailbox = ImapMailbox(settings(PASSWORD))

    @AfterEach
    fun stop() {
        mailbox.close()
        server.stop()
    }

    private fun settings(password: String) = ImapSettings(LOOPBACK, INBOX, Secret(password), port = server.imap.port, tls = false)

    /** Sends a mail over SMTP to the owner's box (the envelope), addressed in its headers as [to], as a catch-all delivers it. */
    private fun deliver(
        subject: String,
        to: String? = null,
        cc: String? = null,
        deliveredTo: String? = null,
    ) {
        val session = Session.getInstance(Properties().apply { put("mail.smtp.host", LOOPBACK) })
        val message =
            MimeMessage(session).apply {
                setFrom(InternetAddress("no-reply@portal.test"))
                to?.let { setRecipient(Message.RecipientType.TO, InternetAddress(it)) }
                cc?.let { setRecipient(Message.RecipientType.CC, InternetAddress(it)) }
                deliveredTo?.let { setHeader("Delivered-To", it) }
                setSubject(subject)
                setText("Təsdiq kodu: 482913")
            }
        session.getTransport("smtp").use { transport ->
            transport.connect(LOOPBACK, server.smtp.port, null, null)
            transport.sendMessage(message, arrayOf(InternetAddress(INBOX)))
        }
    }

    /** A code mail to [to] in a charset no JVM knows: the server has it, but it cannot be read. */
    private fun deliverUnreadable(to: String) =
        deliverRaw(
            """
            From: no-reply@portal.test
            To: $to
            Subject: Kod
            MIME-Version: 1.0
            Content-Type: text/plain; charset="x-unknown-charset-9"

            Kod: 482913
            """.trimIndent().replace("\n", "\r\n"),
        )

    /** Sends [raw] (an RFC 822 message) as it is, over SMTP, to the owner's box. */
    private fun deliverRaw(raw: String) {
        val session = Session.getInstance(Properties().apply { put("mail.smtp.host", LOOPBACK) })
        val message = MimeMessage(session, raw.byteInputStream(Charsets.US_ASCII))
        session.getTransport("smtp").use { transport ->
            transport.connect(LOOPBACK, server.smtp.port, null, null)
            transport.sendMessage(message, arrayOf(InternetAddress(INBOX)))
        }
    }

    @Test
    fun `each tester finds only the mail addressed to its own plus address in the owner's box`() =
        runBlocking<Unit> {
            deliver("Kod A", to = TESTER_A)
            deliver("Kod B", to = TESTER_B)
            deliver("Kod A (surət)", cc = TESTER_A.uppercase())
            deliver("Kod A (catch-all)", deliveredTo = TESTER_A)

            mailbox.findRecent(TESTER_A, since, unreadOnly = false, limit = 10).map { it.subject }.sorted() shouldContainExactly
                listOf("Kod A", "Kod A (catch-all)", "Kod A (surət)")
            mailbox.findLatest(TESTER_B, since).shouldNotBeNull().let {
                it.subject shouldBe "Kod B"
                it.text shouldContain "482913"
            }
            mailbox.findLatest("test+r1-a03@company.test", since).shouldBeNull()
        }

    @Test
    fun `a tester's own mail is found though the server's search also returns many newer mails to lookalike addresses`() =
        runBlocking<Unit> {
            // An IMAP search for an address is a substring search: x + the tester's address matches it as well.
            deliver("Kod A", to = TESTER_A)
            repeat(LOOKALIKES) { deliver("Başqasının kodu $it", to = "x$TESTER_A") }

            mailbox.findLatest(TESTER_A, since).shouldNotBeNull().subject shouldBe "Kod A"
            mailbox.findRecent("x$TESTER_A", since, unreadOnly = true, limit = 100).size shouldBe LOOKALIKES
        }

    @Test
    fun `forty testers asking at once each find only their own mail, and marking all of it read together works`() =
        runBlocking<Unit> {
            // More testers than one search command takes, so a round searches in several commands.
            val testers = (1..FORTY).map { "test+r1-a%02d@company.test".format(it) }
            testers.forEach { deliver("Kod $it", to = it) }

            suspend fun findAll(unreadOnly: Boolean) =
                coroutineScope { testers.map { async(Dispatchers.Default) { mailbox.findLatest(it, since, unreadOnly) } }.awaitAll() }

            val found = findAll(unreadOnly = true)
            found.zip(testers).forEach { (mail, tester) -> mail.shouldNotBeNull().subject shouldBe "Kod $tester" }
            coroutineScope { found.map { async(Dispatchers.Default) { mailbox.markRead(it.shouldNotBeNull().id) } }.awaitAll() }

            findAll(unreadOnly = true).forEach { it.shouldBeNull() }
            findAll(unreadOnly = false).zip(testers).forEach { (mail, tester) ->
                mail.shouldNotBeNull().subject shouldBe "Kod $tester"
                mail.read shouldBe true
            }
        }

    @Test
    fun `a mail that cannot be read fails only its own tester's search, naming it, and the others in the round are answered`() =
        runBlocking<Unit> {
            deliver("Kod A", to = TESTER_A)
            deliverUnreadable(TESTER_B)

            val (a, b) =
                coroutineScope {
                    listOf(TESTER_A, TESTER_B)
                        .map { async(Dispatchers.Default) { runCatching { mailbox.findRecent(it, since, true, 10) } } }
                        .awaitAll()
                }

            a.getOrThrow().map { it.subject } shouldContainExactly listOf("Kod A")
            val error = b.exceptionOrNull().shouldBeInstanceOf<UnreadableMailException>()
            error.message.shouldNotBeNull() shouldContain "Mail to $TESTER_B is in the IMAP inbox $INBOX@$LOOPBACK but cannot be read"
            error.message.shouldNotBeNull() shouldContain "x-unknown-charset-9"
            error.readable.shouldBeEmpty()
        }

    @Test
    fun `a tester whose code mail cannot be read ends with an inbox failure, never with no e-mail sent`() =
        runBlocking<Unit> {
            deliverUnreadable(TESTER_B)
            val verification = DefaultAwaitVerificationUseCase(mailbox, DefaultVerificationExtractor(), lastLookTimeout = 5.seconds)

            val error =
                shouldThrow<MailboxException> {
                    verification.await(TESTER_B, since, MailPurpose.CODE, timeout = 1.seconds, pollInterval = 250.milliseconds)
                }

            error.message.shouldNotBeNull() shouldContain "cannot be read"
        }

    @Test
    fun `a tester's readable code is used though another of its mails cannot be read`() =
        runBlocking<Unit> {
            deliverUnreadable(TESTER_B)
            deliver("Kod B", to = TESTER_B)
            val verification = DefaultAwaitVerificationUseCase(mailbox, DefaultVerificationExtractor())

            verification.await(TESTER_B, since, MailPurpose.CODE, timeout = 10.seconds).code shouldBe "482913"
        }

    @Test
    fun `a code sent right after the wait began is found, though the server keeps its arrival only to the second`() =
        runBlocking<Unit> {
            val waitBegan = Instant.now()

            deliver("Kod A", to = TESTER_A)

            mailbox.findLatest(TESTER_A, waitBegan).shouldNotBeNull().subject shouldBe "Kod A"
        }

    @Test
    fun `reading leaves a mail unread on the server, and marking it read hides it from the next unread search`() =
        runBlocking<Unit> {
            deliver("Kod A", to = TESTER_A)

            val found = mailbox.findLatest(TESTER_A, since, unreadOnly = true).shouldNotBeNull()
            found.read shouldBe false
            mailbox.findLatest(TESTER_A, since, unreadOnly = true).shouldNotBeNull().id shouldBe found.id

            mailbox.markRead(found.id)

            mailbox.findRecent(TESTER_A, since, unreadOnly = true, limit = 5).shouldBeEmpty()
            mailbox.findLatest(TESTER_A, since, unreadOnly = false).shouldNotBeNull().read shouldBe true
        }

    @Test
    fun `a wrong password is reported with the box and the host, never the password`() =
        runBlocking<Unit> {
            val wrong = ImapMailbox(settings("wrong-password-9"))

            val error = shouldThrow<MailboxException> { wrong.findLatest(TESTER_A, since) }

            error.message.shouldNotBeNull().let {
                it shouldContain "$INBOX@$LOOPBACK"
                it shouldNotContain "wrong-password-9"
            }
            wrong.close()
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val INBOX = "test@company.test"
        const val PASSWORD = "imap-password-123"
        const val TESTER_A = "test+r1-a01@company.test"
        const val TESTER_B = "test+r1-a02@company.test"

        const val FORTY = 40

        /** More than the newest 50 matches the inbox once read before telling them apart by the exact address. */
        const val LOOKALIKES = 55
    }
}
