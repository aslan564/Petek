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
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.MailboxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.Properties

class ImapMailboxTest {
    private val settings = ImapSettings("imap.company.az", "test@company.az", Secret("imap-password-123"))
    private val start = Instant.parse("2026-09-26T10:00:00Z")

    private class FakeGateway(
        var messages: List<MailMessage> = emptyList(),
        var failure: Exception? = null,
    ) : ImapGateway {
        val searched = mutableListOf<String>()
        val seen = mutableListOf<String>()
        var closed = false

        override fun candidates(
            recipient: String,
            since: Instant,
        ): List<ImapCandidate> {
            failure?.let { throw it }
            searched += recipient
            return messages.map(::ImapCandidate)
        }

        override fun markSeen(id: String) {
            seen += id
        }

        override fun close() {
            closed = true
        }
    }

    private fun message(
        id: String,
        to: String,
        at: Instant,
        read: Boolean = false,
    ) = MailMessage(id, listOf(to), "Kod", at, "Kodunuz: 123456", null, read)

    private fun mailbox(gateway: FakeGateway) = ImapMailbox(settings, gateway, Dispatchers.Unconfined)

    @Test
    fun `only mail to the tester's exact address, since the start, newest first, unread when asked`() =
        runBlocking<Unit> {
            val gateway =
                FakeGateway(
                    listOf(
                        message("1", "test+r1-a01@company.az", start.plusSeconds(5)),
                        message("2", "test+r1-a02@company.az", start.plusSeconds(6)),
                        message("3", "test@company.az", start.plusSeconds(7)),
                        message("4", "TEST+R1-A01@company.az", start.plusSeconds(9)),
                        message("5", "test+r1-a01@company.az", start.minusSeconds(60)),
                        message("6", "test+r1-a01@company.az", start.plusSeconds(8), read = true),
                    ),
                )

            val found = mailbox(gateway).findRecent("test+r1-a01@company.az", start)

            found.map { it.id } shouldContainExactly listOf("4", "1")
            mailbox(gateway).findRecent("test+r1-a01@company.az", start, unreadOnly = false).map { it.id } shouldContainExactly
                listOf("4", "6", "1")
            gateway.searched.first() shouldBe "test+r1-a01@company.az"
        }

    @Test
    fun `marking read goes to the server and closing closes the connection`() =
        runBlocking<Unit> {
            val gateway = FakeGateway()
            val mailbox = mailbox(gateway)

            mailbox.markRead("42")
            mailbox.close()

            gateway.seen shouldContainExactly listOf("42")
            gateway.closed shouldBe true
        }

    @Test
    fun `a server failure is a mailbox failure naming the host, never the password`() =
        runBlocking<Unit> {
            val gateway = FakeGateway(failure = MessagingException("AUTHENTICATIONFAILED"))

            val error = shouldThrow<MailboxException> { mailbox(gateway).findLatest("test+a01@company.az", start) }

            error.message shouldContain "test@company.az@imap.company.az"
            error.message shouldContain "AUTHENTICATIONFAILED"
            error.message shouldNotContain "imap-password-123"
            settings.toString() shouldNotContain "imap-password-123"
        }

    @Test
    fun `a MIME message is read with its recipients, Delivered-To, text, html and seen flag`() {
        val mime = MimeMessage(Session.getInstance(Properties()))
        mime.setRecipient(Message.RecipientType.TO, InternetAddress("Tester <test+r1-a01@company.az>"))
        mime.setRecipient(Message.RecipientType.CC, InternetAddress("copy@company.az"))
        mime.addHeader("Delivered-To", "test@company.az")
        mime.subject = "Təsdiq kodu"
        mime.sentDate = Date.from(start)
        val text = MimeBodyPart().apply { setText("Kodunuz: 654321", "UTF-8") }
        val html = MimeBodyPart().apply { setContent("<p>Kodunuz: <b>654321</b></p>", "text/html; charset=UTF-8") }
        mime.setContent(MimeMultipart("alternative", text, html))
        mime.setFlag(Flags.Flag.SEEN, true)
        mime.saveChanges()

        val read = MimeMail.read("17", mime)

        read.id shouldBe "17"
        read.to shouldContainExactly listOf("test+r1-a01@company.az", "copy@company.az", "test@company.az")
        read.subject shouldBe "Təsdiq kodu"
        read.text shouldBe "Kodunuz: 654321"
        read.html shouldBe "<p>Kodunuz: <b>654321</b></p>"
        read.receivedAt shouldBe start
        read.read shouldBe true
    }

    @Test
    fun `an html-only message gets its text from the html`() {
        val mime = MimeMessage(Session.getInstance(Properties()))
        mime.setRecipient(Message.RecipientType.TO, InternetAddress("test+a01@company.az"))
        mime.setContent("<style>p{}</style><p>Kod:&nbsp;<b>111222</b></p>", "text/html; charset=UTF-8")
        mime.saveChanges()

        MimeMail.read("1", mime).text shouldBe "Kod: 111222"
    }
}
