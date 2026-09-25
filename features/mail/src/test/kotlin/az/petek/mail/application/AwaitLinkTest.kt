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
import az.petek.mail.domain.VerificationCode
import az.petek.mail.domain.VerificationExtractor
import az.petek.mail.testing.FakeMailbox
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Links of a site's own shape: [VerificationExtractor.extractLink] and [AwaitVerificationUseCase.awaitLink]. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitLinkTest {
    private val extractor = DefaultVerificationExtractor()
    private val mailbox = FakeMailbox()
    private val useCase = DefaultAwaitVerificationUseCase(mailbox, extractor)

    @Test
    fun `a link the built-in hints do not know is found by its pattern`() {
        val invite = mail("m1", "Sizi dəvət etdilər: https://api.kadrohr.test/api/v1/auth/set-password?token=9f1c-77")

        extractor.extract(invite, MailPurpose.LINK).shouldBeNull()
        extractor.extractLink(invite, SET_PASSWORD)?.link shouldBe URI("https://api.kadrohr.test/api/v1/auth/set-password?token=9f1c-77")
    }

    @Test
    fun `the first matching link wins, anchors before text, and the code comes along`() {
        val message =
            mail(
                "m1",
                text = "Kodunuz: 482913. Ətraflı: https://kadrohr.test/help",
                html =
                    """<a href="https://kadrohr.test/help">Kömək</a> <a href="https://api.kadrohr.test/registration/verify?token=a.b.c">Təsdiqlə</a>""",
            )

        val found = extractor.extractLink(message, Regex("registration/verify\\?token="))

        found shouldBe VerificationCode("482913", URI("https://api.kadrohr.test/registration/verify?token=a.b.c"), "m1")
    }

    @Test
    fun `no matching link means no result`() {
        extractor.extractLink(mail("m1", "https://kadrohr.test/help"), SET_PASSWORD).shouldBeNull()
    }

    @Test
    fun `awaitLink waits for the matching mail, skips others and marks only it read`() =
        runTest {
            mailbox.deliver(mail("welcome", "Xoş gəldiniz: https://kadrohr.test/confirm/abc", at = SINCE.plusSeconds(1)))
            launch {
                delay(3.seconds)
                mailbox.deliver(mail("invite", "https://api.kadrohr.test/api/v1/auth/set-password?token=t1", at = SINCE.plusSeconds(4)))
            }

            val found = useCase.awaitLink(ELI, SINCE, SET_PASSWORD, timeout = 60.seconds, pollInterval = 1.seconds)

            found.messageId shouldBe "invite"
            currentTime shouldBe 3_000
            mailbox.messages.single { it.id == "invite" }.read shouldBe true
            mailbox.messages.single { it.id == "welcome" }.read shouldBe false
        }

    @Test
    fun `awaitLink times out like await when no matching link arrives`() =
        runTest {
            mailbox.deliver(mail("welcome", "https://kadrohr.test/confirm/abc"))

            val error = shouldThrow<MailTimeoutException> { useCase.awaitLink(ELI, SINCE, SET_PASSWORD, 5.seconds, 1.seconds) }

            error.timeout shouldBe 5.seconds
            currentTime shouldBe 5_000
        }

    @Test
    fun `an inbox that stays unreachable is reported as such, not as a timeout`() =
        runTest {
            val broken =
                DefaultAwaitVerificationUseCase(
                    object : Mailbox by mailbox {
                        override suspend fun findRecent(
                            to: String,
                            since: Instant,
                            unreadOnly: Boolean,
                            limit: Int,
                        ): List<MailMessage> = throw MailboxException("test API down")
                    },
                    extractor,
                )

            shouldThrow<MailboxException> { broken.awaitLink(ELI, SINCE, SET_PASSWORD, 3.seconds, 1.seconds) }
        }

    @Test
    fun `the default awaitLink accepts the purpose link only when it matches the pattern`() =
        runTest {
            val simple = LinkOnly(URI("https://kadrohr.test/invite/abc"))

            simple.awaitLink(ELI, SINCE, Regex("/invite/")).link shouldBe URI("https://kadrohr.test/invite/abc")
            shouldThrow<MailTimeoutException> { simple.awaitLink(ELI, SINCE, SET_PASSWORD, 7.seconds) }.timeout shouldBe 7.seconds
        }

    @Test
    fun `the default extractLink checks the link the extractor picks`() {
        val basic =
            object : VerificationExtractor {
                override fun extract(
                    message: MailMessage,
                    purpose: MailPurpose,
                ): VerificationCode? = VerificationCode(null, URI("https://kadrohr.test/invite/x"), message.id)
            }

        basic.extractLink(mail("m1", ""), Regex("invite"))?.messageId shouldBe "m1"
        basic.extractLink(mail("m1", ""), SET_PASSWORD).shouldBeNull()
    }

    /** A use case that only implements [await]: it always "finds" [link]. */
    private class LinkOnly(
        private val link: URI,
    ) : AwaitVerificationUseCase {
        override suspend fun await(
            to: String,
            since: Instant,
            purpose: MailPurpose,
            timeout: Duration,
            pollInterval: Duration,
        ): VerificationCode = VerificationCode(null, link, "m1")
    }

    private companion object {
        const val ELI = "eli.k7x2.a07@test.kadrohr.com"
        val SINCE: Instant = Instant.parse("2026-09-25T10:00:00Z")
        val SET_PASSWORD = Regex("set-password\\?token=")

        fun mail(
            id: String,
            text: String,
            html: String? = null,
            at: Instant = SINCE.plusSeconds(1),
        ) = MailMessage(id, listOf(ELI), "Kadro HR", at, text, html, read = false)
    }
}
