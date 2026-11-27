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

package az.petek.mail.application

import az.petek.mail.domain.DefaultVerificationExtractor
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.MailboxException
import az.petek.mail.domain.UnreadableMailException
import az.petek.mail.testing.FakeMailbox
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAwaitVerificationUseCaseTest {
    private val mailbox = CountingMailbox(FakeMailbox())
    private val useCase = DefaultAwaitVerificationUseCase(mailbox, DefaultVerificationExtractor())

    @Test
    fun `returns the code of a mail that arrives after three empty polls`() =
        runTest {
            launch {
                delay(2_500)
                mailbox.fake.deliver(codeMail("m1", "482913", at = SINCE.plusSeconds(2)))
            }

            val result = useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 60.seconds, pollInterval = 1.seconds)

            result.code shouldBe "482913"
            result.messageId shouldBe "m1"
            mailbox.polls shouldBe 4
            currentTime shouldBe 3_000
        }

    @Test
    fun `a mail that is already there is used on the first poll`() =
        runTest {
            mailbox.fake.deliver(codeMail("m1", "482913"))

            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "482913"

            mailbox.polls shouldBe 1
            currentTime shouldBe 0
        }

    @Test
    fun `a code the mailbox keeps to the second is found when it came in the same second as the wait began`() =
        runTest {
            // Like an IMAP server: arrivals to the second, and a search from the start of the wait's second.
            val seconds =
                object : Mailbox by mailbox.fake {
                    override suspend fun findRecent(
                        to: String,
                        since: Instant,
                        unreadOnly: Boolean,
                        limit: Int,
                    ): List<MailMessage> =
                        mailbox.fake
                            .findRecent(to, since.truncatedTo(ChronoUnit.SECONDS), unreadOnly, limit)
                            .map { it.copy(receivedAt = it.receivedAt.truncatedTo(ChronoUnit.SECONDS)) }
                }
            val waitBegan = SINCE.plusMillis(400)
            mailbox.fake.deliver(codeMail("m1", "482913", at = SINCE.plusMillis(780)))

            val result =
                DefaultAwaitVerificationUseCase(seconds, DefaultVerificationExtractor())
                    .await(ELI, waitBegan, MailPurpose.CODE, timeout = 5.seconds, pollInterval = 1.seconds)

            result.code shouldBe "482913"
            currentTime shouldBe 0
        }

    @Test
    fun `throws a mail timeout when nothing arrives in time`() =
        runTest {
            val error =
                shouldThrow<MailTimeoutException> {
                    useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds, pollInterval = 1.seconds)
                }

            error.to shouldBe ELI
            error.timeout shouldBe 5.seconds
            currentTime shouldBe 5_000
            // Five polls within the time and the last look at the deadline.
            mailbox.polls shouldBe 6
        }

    @Test
    fun `a code that arrived before the deadline while the inbox answered slowly is found by the last look, not reported as a timeout`() =
        runTest {
            // A shared inbox busy with other testers: each look waits 4 s for its turn and then sees what is there.
            val busy =
                object : Mailbox by mailbox {
                    override suspend fun findRecent(
                        to: String,
                        since: Instant,
                        unreadOnly: Boolean,
                        limit: Int,
                    ): List<MailMessage> {
                        delay(4_000)
                        return mailbox.findRecent(to, since, unreadOnly, limit)
                    }
                }
            launch {
                delay(5_000)
                mailbox.fake.deliver(codeMail("m1", "482913", at = SINCE.plusSeconds(5)))
            }

            val result =
                DefaultAwaitVerificationUseCase(busy, DefaultVerificationExtractor())
                    .await(ELI, SINCE, MailPurpose.CODE, timeout = 6.seconds, pollInterval = 1.seconds)

            // The second poll (from 5 s) was cut at the 6 s deadline; the last look waited its turn and found the code.
            result.code shouldBe "482913"
            mailbox.markedRead shouldContainExactly listOf("m1")
            currentTime shouldBe 10_000
        }

    @Test
    fun `an inbox that fails the last look is an inbox failure, not a mail timeout`() =
        runTest {
            val failingAtTheEnd =
                object : Mailbox by mailbox {
                    override suspend fun findRecent(
                        to: String,
                        since: Instant,
                        unreadOnly: Boolean,
                        limit: Int,
                    ): List<MailMessage> {
                        val found = mailbox.findRecent(to, since, unreadOnly, limit)
                        if (mailbox.polls > 5) throw MailboxException("IMAP connection dropped")
                        return found
                    }
                }

            val error =
                shouldThrow<MailboxException> {
                    DefaultAwaitVerificationUseCase(failingAtTheEnd, DefaultVerificationExtractor())
                        .await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds, pollInterval = 1.seconds)
                }

            error.message shouldBe "IMAP connection dropped"
        }

    @Test
    fun `an inbox that does not answer the last look in time is an inbox failure, bounded by the last look's own limit`() =
        runTest {
            val stuckAtTheEnd =
                object : Mailbox by mailbox {
                    override suspend fun findRecent(
                        to: String,
                        since: Instant,
                        unreadOnly: Boolean,
                        limit: Int,
                    ): List<MailMessage> {
                        val found = mailbox.findRecent(to, since, unreadOnly, limit)
                        if (mailbox.polls > 5) delay(1.hours)
                        return found
                    }
                }

            val error =
                shouldThrow<MailboxException> {
                    DefaultAwaitVerificationUseCase(stuckAtTheEnd, DefaultVerificationExtractor(), lastLookTimeout = 20.seconds)
                        .await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds, pollInterval = 1.seconds)
                }

            error.message shouldContain "did not answer the last look for mail to $ELI within 20s"
            currentTime shouldBe 25_000
        }

    @Test
    fun `marks only the used message read`() =
        runTest {
            mailbox.fake.deliver(codeMail("old-code", "111111", at = SINCE.plusSeconds(1)))
            mailbox.fake.deliver(codeMail("new-code", "222222", at = SINCE.plusSeconds(2)))

            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "222222"

            mailbox.markedRead shouldContainExactly listOf("new-code")
            mailbox.fake.messages
                .single { it.id == "old-code" }
                .read shouldBe false
        }

    @Test
    fun `a newer mail without a code is skipped and left unread while the older code mail is used`() =
        runTest {
            mailbox.fake.deliver(codeMail("code", "482913", at = SINCE.plusSeconds(1)))
            mailbox.fake.deliver(mail("welcome", "Xoş gəlmisiniz", "Hesabınız hazırdır.", at = SINCE.plusSeconds(2)))

            val result = useCase.await(ELI, SINCE, MailPurpose.CODE)

            result.messageId shouldBe "code"
            mailbox.fake.messages
                .single { it.id == "welcome" }
                .read shouldBe false
        }

    @Test
    fun `a wrong-purpose mail is never consumed and the right mail is used when it arrives`() =
        runTest {
            mailbox.fake.deliver(mail("invite", "Dəvət", "Qəbul et: https://x.example/invite/tok", at = SINCE.plusSeconds(1)))
            launch {
                delay(3_000)
                mailbox.fake.deliver(codeMail("code", "553311", at = SINCE.plusSeconds(4)))
            }

            val result = useCase.await(ELI, SINCE, MailPurpose.CODE, pollInterval = 1.seconds)

            result.code shouldBe "553311"
            mailbox.markedRead shouldContainExactly listOf("code")
            mailbox.fake.messages
                .single { it.id == "invite" }
                .read shouldBe false
            useCase.await(ELI, SINCE, MailPurpose.LINK).link.toString() shouldBe "https://x.example/invite/tok"
        }

    @Test
    fun `mail received before since is never used`() =
        runTest {
            mailbox.fake.deliver(codeMail("before-run", "111111", at = SINCE.minusSeconds(1)))

            shouldThrow<MailTimeoutException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 3.seconds) }
            mailbox.markedRead shouldBe emptyList()
        }

    @Test
    fun `a mail received exactly at since counts`() =
        runTest {
            mailbox.fake.deliver(codeMail("at-start", "111111", at = SINCE))

            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "111111"
        }

    @Test
    fun `an already read code is never reused`() =
        runTest {
            mailbox.fake.deliver(codeMail("used", "111111").copy(read = true))

            shouldThrow<MailTimeoutException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 3.seconds) }
        }

    @Test
    fun `waiting twice for the same recipient never returns the same message`() =
        runTest {
            mailbox.fake.deliver(codeMail("first", "111111", at = SINCE.plusSeconds(1)))
            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "111111"

            launch {
                delay(2_000)
                mailbox.fake.deliver(codeMail("resent", "222222", at = SINCE.plusSeconds(5)))
            }
            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "222222"
        }

    @Test
    fun `mail for another tester is ignored`() =
        runTest {
            mailbox.fake.deliver(codeMail("other", "111111", to = "veli.k7x2.a08@test.portal.example"))

            shouldThrow<MailTimeoutException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 2.seconds) }
        }

    @Test
    fun `waits for a link when the purpose is LINK`() =
        runTest {
            mailbox.fake.deliver(codeMail("code", "482913", at = SINCE.plusSeconds(2)))
            mailbox.fake.deliver(
                mail(
                    "invite",
                    "Dəvət",
                    "<p>x</p>",
                    html = "<a href=\"https://x.example/invite/abc\">Qəbul et</a>",
                    at = SINCE.plusSeconds(1),
                ),
            )

            val result = useCase.await(ELI, SINCE, MailPurpose.LINK)

            result.link.toString() shouldBe "https://x.example/invite/abc"
            result.messageId shouldBe "invite"
            mailbox.fake.messages
                .single { it.id == "code" }
                .read shouldBe false
        }

    @Test
    fun `a transient mailbox failure is retried on the next poll`() =
        runTest {
            mailbox.fake.deliver(codeMail("m1", "482913"))
            mailbox.failingPolls = 2

            useCase.await(ELI, SINCE, MailPurpose.CODE, pollInterval = 1.seconds).code shouldBe "482913"

            mailbox.polls shouldBe 3
            currentTime shouldBe 2_000
        }

    @Test
    fun `a mailbox that is still failing at the deadline surfaces its error instead of a mail timeout`() =
        runTest {
            mailbox.failingPolls = Int.MAX_VALUE

            val error = shouldThrow<MailboxException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 3.seconds) }

            error.message shouldBe "Mailpit is down (poll ${mailbox.polls})"
        }

    @Test
    fun `mail that is there but cannot be read ends the wait as an inbox failure, and a readable code beside it is still used`() =
        runTest {
            // The inbox holds a mail to the tester it cannot read, and answers with what it could read.
            val partly =
                object : Mailbox by mailbox {
                    override suspend fun findRecent(
                        to: String,
                        since: Instant,
                        unreadOnly: Boolean,
                        limit: Int,
                    ): List<MailMessage> {
                        val readable = mailbox.findRecent(to, since, unreadOnly, limit)
                        throw UnreadableMailException("Mail to $to is in the inbox but cannot be read: message 9 (bad charset)", readable)
                    }
                }
            val useCase = DefaultAwaitVerificationUseCase(partly, DefaultVerificationExtractor())
            mailbox.fake.deliver(mail("welcome", "Xoş gəlmisiniz", "Hesabınız hazırdır."))

            shouldThrow<UnreadableMailException> {
                useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds)
            }.message shouldContain "message 9"
            mailbox.fake.deliver(codeMail("code", "482913", at = SINCE.plusSeconds(2)))
            useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds).code shouldBe "482913"
        }

    @Test
    fun `a mailbox that recovered before the deadline ends in a mail timeout`() =
        runTest {
            mailbox.failingPolls = 2

            shouldThrow<MailTimeoutException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds) }
        }

    @Test
    fun `a code whose mark fails is returned at once, and its message is never handed out again`() =
        runTest {
            mailbox.fake.deliver(codeMail("m1", "482913"))
            mailbox.failingMarkReads = Int.MAX_VALUE

            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "482913"

            mailbox.polls shouldBe 1
            currentTime shouldBe 0
            // The inbox still shows it unread, yet a later wait of the same tester never reuses the old code.
            mailbox.fake.messages
                .single()
                .read shouldBe false
            shouldThrow<MailTimeoutException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 3.seconds) }
            launch {
                delay(1_500)
                mailbox.fake.deliver(codeMail("m2", "553311", at = SINCE.plusSeconds(9)))
            }
            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "553311"
        }

    @Test
    fun `a code whose mark set the flag on the server and then failed is still returned`() =
        runTest {
            // Like an IMAP round whose STORE was applied but whose CLOSE then timed out.
            val flaggedThenFailed =
                object : Mailbox by mailbox {
                    override suspend fun markRead(messageId: String) {
                        mailbox.fake.markRead(messageId)
                        throw MailboxException("IMAP CLOSE timed out")
                    }
                }
            launch {
                delay(2_500)
                mailbox.fake.deliver(codeMail("m1", "482913", at = SINCE.plusSeconds(2)))
            }

            val result =
                DefaultAwaitVerificationUseCase(flaggedThenFailed, DefaultVerificationExtractor())
                    .await(ELI, SINCE, MailPurpose.CODE, timeout = 30.seconds, pollInterval = 1.seconds)

            result.code shouldBe "482913"
            currentTime shouldBe 3_000
        }

    @Test
    fun `a code whose mark the deadline cut off after the flag was set is still returned, its mark tried once more`() =
        runTest {
            // A slow shared inbox: the flag is set at once, but the answer to the mark comes two seconds later.
            val slowMark =
                object : Mailbox by mailbox {
                    override suspend fun markRead(messageId: String) {
                        mailbox.markRead(messageId)
                        delay(2_000)
                    }
                }
            launch {
                delay(3_500)
                mailbox.fake.deliver(codeMail("m1", "482913", at = SINCE.plusSeconds(3)))
            }

            val result =
                DefaultAwaitVerificationUseCase(slowMark, DefaultVerificationExtractor())
                    .await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds, pollInterval = 1.seconds)

            // Found by the poll at 4 s; the deadline at 5 s cut its mark; the second mark ended at 7 s.
            result.code shouldBe "482913"
            mailbox.markedRead shouldContainExactly listOf("m1", "m1")
            mailbox.polls shouldBe 5
            currentTime shouldBe 7_000
        }

    @Test
    fun `a code taken by the last look is returned though its mark is not answered within the last look's limit`() =
        runTest {
            val stuckMark =
                object : Mailbox by mailbox {
                    override suspend fun markRead(messageId: String) {
                        mailbox.markRead(messageId)
                        delay(1.hours)
                    }
                }
            // Arrives after the last poll within the time, so only the last look sees it.
            launch {
                delay(4_500)
                mailbox.fake.deliver(codeMail("m1", "482913", at = SINCE.plusSeconds(4)))
            }
            val useCase = DefaultAwaitVerificationUseCase(stuckMark, DefaultVerificationExtractor(), lastLookTimeout = 20.seconds)

            useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds, pollInterval = 1.seconds).code shouldBe "482913"

            currentTime shouldBe 25_000
        }

    @Test
    fun `cancelling the caller stops polling`() =
        runTest {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 60.seconds) }
            advanceTimeBy(2_500.milliseconds)
            waiting.cancel()
            runCurrent()
            val pollsAtCancel = mailbox.polls

            advanceTimeBy(10.seconds)

            waiting.isCancelled shouldBe true
            mailbox.polls shouldBe pollsAtCancel
        }

    @Test
    fun `rejects a non-positive timeout or poll interval`() =
        runTest {
            shouldThrow<IllegalArgumentException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 0.seconds) }
            shouldThrow<IllegalArgumentException> { useCase.await(ELI, SINCE, MailPurpose.CODE, pollInterval = 0.seconds) }
            shouldThrow<IllegalArgumentException> { DefaultAwaitVerificationUseCase(mailbox, DefaultVerificationExtractor(), 0) }
            shouldThrow<IllegalArgumentException> {
                DefaultAwaitVerificationUseCase(mailbox, DefaultVerificationExtractor(), lastLookTimeout = 0.seconds)
            }
        }

    /** Counts polls and can fail the first N polls or markReads, like an unreachable Mailpit would. */
    private class CountingMailbox(
        val fake: FakeMailbox,
    ) : Mailbox by fake {
        var polls = 0
        var failingPolls = 0
        var failingMarkReads = 0
        val markedRead = mutableListOf<String>()

        override suspend fun findRecent(
            to: String,
            since: Instant,
            unreadOnly: Boolean,
            limit: Int,
        ): List<MailMessage> {
            polls++
            if (polls <= failingPolls) throw MailboxException("Mailpit is down (poll $polls)")
            return fake.findRecent(to, since, unreadOnly, limit)
        }

        override suspend fun markRead(messageId: String) {
            if (failingMarkReads-- > 0) throw MailboxException("Mailpit is down (markRead)")
            markedRead += messageId
            fake.markRead(messageId)
        }
    }

    private companion object {
        const val ELI = "eli.k7x2.a07@test.portal.example"
        val SINCE: Instant = Instant.parse("2026-09-25T10:00:00Z")

        fun mail(
            id: String,
            subject: String,
            text: String,
            html: String? = null,
            at: Instant = SINCE.plusSeconds(1),
            to: String = ELI,
        ) = MailMessage(id, listOf(to), subject, at, text, html, read = false)

        fun codeMail(
            id: String,
            code: String,
            at: Instant = SINCE.plusSeconds(1),
            to: String = ELI,
        ) = mail(id, "Təsdiq kodu", "Sizin təsdiq kodunuz: $code", at = at, to = to)
    }
}
