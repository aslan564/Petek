/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.application

import az.petek.mail.domain.DefaultVerificationExtractor
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.MailboxException
import az.petek.mail.testing.FakeMailbox
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
    fun `throws a mail timeout when nothing arrives in time`() =
        runTest {
            val error =
                shouldThrow<MailTimeoutException> {
                    useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds, pollInterval = 1.seconds)
                }

            error.to shouldBe ELI
            error.timeout shouldBe 5.seconds
            currentTime shouldBe 5_000
            mailbox.polls shouldBe 5
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
            mailbox.fake.deliver(mail("invite", "Dəvət", "Qəbul et: https://x.az/invite/tok", at = SINCE.plusSeconds(1)))
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
            useCase.await(ELI, SINCE, MailPurpose.LINK).link.toString() shouldBe "https://x.az/invite/tok"
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
            mailbox.fake.deliver(codeMail("other", "111111", to = "veli.k7x2.a08@test.kadrohr.com"))

            shouldThrow<MailTimeoutException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 2.seconds) }
        }

    @Test
    fun `waits for a link when the purpose is LINK`() =
        runTest {
            mailbox.fake.deliver(codeMail("code", "482913", at = SINCE.plusSeconds(2)))
            mailbox.fake.deliver(
                mail("invite", "Dəvət", "<p>x</p>", html = "<a href=\"https://x.az/invite/abc\">Qəbul et</a>", at = SINCE.plusSeconds(1)),
            )

            val result = useCase.await(ELI, SINCE, MailPurpose.LINK)

            result.link.toString() shouldBe "https://x.az/invite/abc"
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
    fun `a mailbox that recovered before the deadline ends in a mail timeout`() =
        runTest {
            mailbox.failingPolls = 2

            shouldThrow<MailTimeoutException> { useCase.await(ELI, SINCE, MailPurpose.CODE, timeout = 5.seconds) }
        }

    @Test
    fun `a failed markRead is retried and the code is returned once the mail is marked read`() =
        runTest {
            mailbox.fake.deliver(codeMail("m1", "482913"))
            mailbox.failingMarkReads = 1

            useCase.await(ELI, SINCE, MailPurpose.CODE).code shouldBe "482913"

            mailbox.fake.messages
                .single()
                .read shouldBe true
            mailbox.polls shouldBe 2
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
        const val ELI = "eli.k7x2.a07@test.kadrohr.com"
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
