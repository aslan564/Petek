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
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.MailboxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ImapMailboxTest {
    private val settings = ImapSettings("imap.company.example", "test@company.example", Secret("imap-password-123"))
    private val start = Instant.parse("2026-09-26T10:00:00Z")

    /**
     * The server side of [ImapMailbox]: each conversation takes [latency] (coroutine time) and then answers from the
     * inbox as it is at that moment. It answers every search with the whole inbox, so the mailbox has to tell the
     * testers apart itself.
     */
    private class FakeGateway(
        var messages: List<MailMessage> = emptyList(),
        var failure: Exception? = null,
        private val latency: Duration = Duration.ZERO,
        /** How many conversations [failure] fails; all of them by default. */
        private var failures: Int = Int.MAX_VALUE,
    ) : ImapGateway {
        /** The recipients of each search conversation, in order. */
        val searched = mutableListOf<List<String>>()
        val seen = mutableListOf<String>()
        var conversations = 0
        var closed = false

        fun deliver(message: MailMessage) {
            messages = messages + message
        }

        override suspend fun find(queries: List<ImapQuery>): List<List<MailMessage>> {
            conversations++
            delay(latency)
            failure?.takeIf { failures-- > 0 }?.let { throw it }
            searched += queries.map { it.recipient }
            return queries.map { messages }
        }

        override suspend fun markSeen(ids: Collection<String>) {
            conversations++
            delay(latency)
            failure?.takeIf { failures-- > 0 }?.let { throw it }
            seen += ids
            messages = messages.map { if (it.id in ids) it.copy(read = true) else it }
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

    private fun codeMail(
        id: String,
        to: String,
        at: Instant,
        code: String,
    ) = MailMessage(id, listOf(to), "Təsdiq kodu", at, "Kodunuz: $code", null, read = false)

    private fun tester(n: Int) = "test+r1-a%03d@company.example".format(n)

    private fun TestScope.mailbox(gateway: FakeGateway) = ImapMailbox(settings, gateway, backgroundScope)

    @Test
    fun `only mail to the tester's exact address, since the start, newest first, unread when asked`() =
        runTest {
            val gateway =
                FakeGateway(
                    listOf(
                        message("1", "test+r1-a01@company.example", start.plusSeconds(5)),
                        message("2", "test+r1-a02@company.example", start.plusSeconds(6)),
                        message("3", "test@company.example", start.plusSeconds(7)),
                        message("4", "TEST+R1-A01@company.example", start.plusSeconds(9)),
                        message("5", "test+r1-a01@company.example", start.minusSeconds(60)),
                        message("6", "test+r1-a01@company.example", start.plusSeconds(8), read = true),
                    ),
                )

            val found = mailbox(gateway).findRecent("test+r1-a01@company.example", start)

            found.map { it.id } shouldContainExactly listOf("4", "1")
            mailbox(gateway).findRecent("test+r1-a01@company.example", start, unreadOnly = false).map { it.id } shouldContainExactly
                listOf("4", "6", "1")
            gateway.searched.first() shouldBe listOf("test+r1-a01@company.example")
        }

    @Test
    fun `a code that arrived in the same second as the wait began counts, as the server keeps arrivals to the second`() =
        runTest {
            val waitBegan = start.plusMillis(700)
            val gateway =
                FakeGateway(
                    listOf(
                        message("1", "test+r1-a01@company.example", start),
                        message("2", "test+r1-a01@company.example", start.minusSeconds(1)),
                    ),
                )

            mailbox(gateway).findRecent("test+r1-a01@company.example", waitBegan).map { it.id } shouldContainExactly listOf("1")
        }

    @Test
    fun `marking read goes to the server and closing closes the connection`() =
        runTest {
            val gateway = FakeGateway()
            val mailbox = mailbox(gateway)

            mailbox.markRead("42")
            mailbox.close()

            gateway.seen shouldContainExactly listOf("42")
            gateway.closed shouldBe true
        }

    @Test
    fun `a server failure is a mailbox failure naming the host, never the password`() =
        runTest {
            val gateway = FakeGateway(failure = MessagingException("AUTHENTICATIONFAILED"))

            val error = shouldThrow<MailboxException> { mailbox(gateway).findLatest("test+a01@company.example", start) }

            error.message shouldContain "test@company.example@imap.company.example"
            error.message shouldContain "AUTHENTICATIONFAILED"
            error.message shouldNotContain "imap-password-123"
            settings.toString() shouldNotContain "imap-password-123"
        }

    @Test
    fun `a hundred testers waiting at once each get their own code within seconds of its arrival, well within the timeout`() =
        runTest {
            // Each conversation with the (remote) server takes 0.6 s: one conversation per tester would take a minute.
            val gateway = FakeGateway(latency = 600.milliseconds)
            val verification = DefaultAwaitVerificationUseCase(mailbox(gateway), DefaultVerificationExtractor())
            val testers = (1..TESTERS).map(::tester)

            fun arrival(index: Int) = 5_000L + index * 500L
            testers.forEachIndexed { index, tester ->
                launch {
                    delay(arrival(index))
                    gateway.deliver(codeMail("${index + 1}", tester, start.plusMillis(arrival(index)), "${100_000 + index}"))
                }
            }

            val waits =
                testers
                    .mapIndexed { index, tester ->
                        async {
                            val found = verification.await(tester, start, MailPurpose.CODE, timeout = 60.seconds, pollInterval = 1.seconds)
                            Triple(index, found, currentTime - arrival(index))
                        }
                    }.awaitAll()

            waits.forEach { (index, found, _) ->
                found.code shouldBe "${100_000 + index}"
                found.messageId shouldBe "${index + 1}"
            }
            waits.maxOf { it.third } shouldBeLessThanOrEqual 6_000
            gateway.messages.count { it.read } shouldBe TESTERS
            gateway.searched.maxOf { it.size } shouldBeGreaterThan TESTERS / 2
        }

    @Test
    fun `when the site sent a hundred testers nothing, they all learn it together at the deadline, not one after another`() =
        runTest {
            val gateway = FakeGateway(latency = 600.milliseconds)
            val verification = DefaultAwaitVerificationUseCase(mailbox(gateway), DefaultVerificationExtractor())

            val outcomes =
                (1..TESTERS)
                    .map { n ->
                        async {
                            runCatching {
                                verification.await(
                                    tester(n),
                                    start,
                                    MailPurpose.CODE,
                                    timeout = 60.seconds,
                                    pollInterval = 1.seconds,
                                )
                            }
                        }
                    }.awaitAll()

            outcomes.forEach { it.exceptionOrNull().shouldBeInstanceOf<MailTimeoutException>() }
            // The deadline, then the last looks of all hundred, which share the round in progress and one more.
            currentTime shouldBeLessThanOrEqual 62_000
        }

    @Test
    fun `a tester that stops waiting is left out of the next round, and the others are still answered`() =
        runTest {
            val gateway = FakeGateway(listOf(message("1", tester(2), start.plusSeconds(1))), latency = 1.seconds)
            val mailbox = mailbox(gateway)
            val first = async { mailbox.findRecent(tester(1), start) }
            runCurrent()
            val gaveUp = async { mailbox.findRecent(tester(3), start) }
            val other = async { mailbox.findRecent(tester(2), start) }
            runCurrent()

            gaveUp.cancel()

            other.await().map { it.id } shouldContainExactly listOf("1")
            first.await() shouldBe emptyList()
            gateway.searched shouldContainExactly listOf(listOf(tester(1)), listOf(tester(2)))
        }

    @Test
    fun `a failed round fails only the requests in it, each naming its own address, and the next round answers`() =
        runTest {
            val gateway =
                FakeGateway(
                    listOf(message("1", tester(2), start.plusSeconds(1))),
                    failure = MessagingException("connection reset"),
                    latency = 1.seconds,
                    failures = 1,
                )
            val mailbox = mailbox(gateway)
            val failed = async { runCatching { mailbox.findRecent(tester(1), start) } }
            runCurrent()
            val later = async { mailbox.findRecent(tester(2), start) }

            failed
                .await()
                .exceptionOrNull()
                .shouldBeInstanceOf<MailboxException>()
                .message shouldBe
                "Cannot search for mail to ${tester(1)} in the IMAP inbox test@company.example@imap.company.example: connection reset"
            later.await().map { it.id } shouldContainExactly listOf("1")
        }

    @Test
    fun `closing the inbox fails the testers still waiting instead of leaving them waiting`() =
        runTest {
            val gateway = FakeGateway(latency = 1.seconds)
            val mailbox = mailbox(gateway)
            val inRound = async { runCatching { mailbox.findRecent(tester(1), start) } }
            runCurrent()
            val queued = async { runCatching { mailbox.markRead("7") } }
            runCurrent()

            mailbox.close()

            inRound
                .await()
                .exceptionOrNull()
                .shouldBeInstanceOf<MailboxException>()
                .message shouldContain "is closed"
            queued
                .await()
                .exceptionOrNull()
                .shouldBeInstanceOf<MailboxException>()
                .message shouldContain "is closed"
            shouldThrow<MailboxException> { mailbox.findLatest(tester(3), start) }.message shouldContain "is closed"
            gateway.closed shouldBe true
        }

    @Test
    fun `a tester's own message is chosen though far more and newer messages to lookalike addresses came in the same round`() {
        val own = ImapEnvelope(1, listOf(tester(1)), start.plusSeconds(1), read = false)
        val lookalikes = (2L..200L).map { ImapEnvelope(it, listOf("x${tester(1)}"), start.plusSeconds(it), read = false) }

        ImapSelection.select(listOf(ImapQuery.of(tester(1), start, unreadOnly = true, limit = 10)), lookalikes + own) shouldContainExactly
            listOf(listOf(1L))
    }

    @Test
    fun `each query of a round gets its own messages, newest first, the higher UID first within one second`() {
        val envelopes =
            listOf(
                ImapEnvelope(7, listOf(tester(1)), start.plusSeconds(3), read = false),
                ImapEnvelope(9, listOf(tester(1).uppercase()), start.plusSeconds(3), read = false),
                ImapEnvelope(8, listOf(tester(2)), start.plusSeconds(4), read = false),
                ImapEnvelope(5, listOf(tester(1)), start.plusSeconds(2), read = true),
                ImapEnvelope(4, listOf(tester(1)), start.minusSeconds(1), read = false),
            )
        val queries =
            listOf(
                ImapQuery.of(tester(1), start, unreadOnly = true, limit = 10),
                ImapQuery.of(tester(2), start, unreadOnly = true, limit = 10),
                ImapQuery.of(tester(1), start, unreadOnly = false, limit = 2),
                ImapQuery.of(tester(3), start, unreadOnly = false, limit = 10),
            )

        ImapSelection.select(queries, envelopes) shouldContainExactly listOf(listOf(9L, 7L), listOf(8L), listOf(9L, 7L), emptyList())
    }

    @Test
    fun `the message cache keeps the most recently used within its size and forgets everything when UIDVALIDITY changes`() {
        val cache = MessageCache(maxChars = 30)

        fun mail(id: Long) = MailMessage("$id", listOf(tester(1)), "Kod", start, "0123456789", null, read = false)

        cache.keepFor(1) shouldBe false
        (1L..3L).forEach { cache.put(it, mail(it)) }
        cache[1].shouldNotBeNull()
        cache.put(4, mail(4))

        cache[2].shouldBeNull()
        listOf(1L, 3L, 4L).forEach { cache[it].shouldNotBeNull() }
        cache.keepFor(1) shouldBe false
        cache.size shouldBe 3
        cache.keepFor(2) shouldBe true
        cache.size shouldBe 0
    }

    @Test
    fun `the search finds mail that names the tester only in its delivery headers, as a catch-all inbox or a Bcc leaves it`() {
        fun mail(configure: MimeMessage.() -> Unit) =
            MimeMessage(Session.getInstance(Properties())).apply {
                setRecipient(Message.RecipientType.TO, InternetAddress("someone-else@company.example"))
                setText("Kodunuz: 654321", "UTF-8")
                configure()
                saveChanges()
            }
        val search = ImapSearch.addressedTo("test+r1-a01@company.example")

        search.match(mail { setRecipient(Message.RecipientType.TO, InternetAddress("test+r1-a01@company.example")) }) shouldBe true
        search.match(mail { setRecipient(Message.RecipientType.CC, InternetAddress("test+r1-a01@company.example")) }) shouldBe true
        search.match(mail { addHeader("Delivered-To", "test+r1-a01@company.example") }) shouldBe true
        search.match(mail { addHeader("X-Original-To", "test+r1-a01@company.example") }) shouldBe true
        search.match(mail { addHeader("Delivered-To", "test+r1-a02@company.example") }) shouldBe false
        MimeMail.read("1", mail { addHeader("X-Original-To", "test+r1-a01@company.example") }).to shouldContain
            "test+r1-a01@company.example"
    }

    @Test
    fun `a MIME message is read with its recipients, Delivered-To, text, html and seen flag`() {
        val mime = MimeMessage(Session.getInstance(Properties()))
        mime.setRecipient(Message.RecipientType.TO, InternetAddress("Tester <test+r1-a01@company.example>"))
        mime.setRecipient(Message.RecipientType.CC, InternetAddress("copy@company.example"))
        mime.addHeader("Delivered-To", "test@company.example")
        mime.subject = "Təsdiq kodu"
        mime.sentDate = Date.from(start)
        val text = MimeBodyPart().apply { setText("Kodunuz: 654321", "UTF-8") }
        val html = MimeBodyPart().apply { setContent("<p>Kodunuz: <b>654321</b></p>", "text/html; charset=UTF-8") }
        mime.setContent(MimeMultipart("alternative", text, html))
        mime.setFlag(Flags.Flag.SEEN, true)
        mime.saveChanges()

        val read = MimeMail.read("17", mime)

        read.id shouldBe "17"
        read.to shouldContainExactly listOf("test+r1-a01@company.example", "copy@company.example", "test@company.example")
        read.subject shouldBe "Təsdiq kodu"
        read.text shouldBe "Kodunuz: 654321"
        read.html shouldBe "<p>Kodunuz: <b>654321</b></p>"
        read.receivedAt shouldBe start
        read.read shouldBe true
    }

    @Test
    fun `an html-only message gets its text from the html`() {
        val mime = MimeMessage(Session.getInstance(Properties()))
        mime.setRecipient(Message.RecipientType.TO, InternetAddress("test+a01@company.example"))
        mime.setContent("<style>p{}</style><p>Kod:&nbsp;<b>111222</b></p>", "text/html; charset=UTF-8")
        mime.saveChanges()

        MimeMail.read("1", mime).text shouldBe "Kod: 111222"
    }

    private companion object {
        /** The most testers a run has. */
        const val TESTERS = 100
    }
}
