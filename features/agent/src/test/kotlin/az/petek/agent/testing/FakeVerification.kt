/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.testing

import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.MailboxException
import az.petek.mail.domain.VerificationCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Inbox-like [AwaitVerificationUseCase]: tests (or the simulated site) "send" codes and links; like the real use case
 * (`Mailbox.findLatest`), each await consumes the newest unread one for that address, polling in virtual time and
 * throwing [MailTimeoutException] when nothing arrives. Older unread messages stay unread. With an [outage] the inbox
 * cannot be read at all: like the real use case, an await then keeps trying for its whole timeout and throws the
 * [MailboxException].
 */
class FakeVerification : AwaitVerificationUseCase {
    private data class Mail(
        val to: String,
        val code: VerificationCode,
    )

    private val inbox = mutableListOf<Mail>()
    private val ids = AtomicInteger()

    /** Every await call: address and purpose ([awaitLink] counts as [MailPurpose.LINK]). */
    val calls = CopyOnWriteArrayList<Pair<String, MailPurpose>>()

    /** The pattern of every [awaitLink] call. */
    val linkPatterns = CopyOnWriteArrayList<String>()

    /** Set to make the inbox unreachable. */
    @Volatile
    var outage: MailboxException? = null

    fun sendCode(
        to: String,
        code: String,
    ) = deliver(to, VerificationCode(code, null, "msg-${ids.incrementAndGet()}"))

    fun sendLink(
        to: String,
        link: String,
    ) = deliver(to, VerificationCode(null, URI(link), "msg-${ids.incrementAndGet()}"))

    private fun deliver(
        to: String,
        code: VerificationCode,
    ) {
        synchronized(inbox) { inbox += Mail(to.lowercase(), code) }
    }

    override suspend fun await(
        to: String,
        since: Instant,
        purpose: MailPurpose,
        timeout: Duration,
        pollInterval: Duration,
    ): VerificationCode {
        calls += to to purpose
        return awaitMatching(to, timeout, pollInterval) { code ->
            when (purpose) {
                MailPurpose.CODE -> code.code != null
                MailPurpose.LINK -> code.link != null
                MailPurpose.ANY -> true
            }
        }
    }

    override suspend fun awaitLink(
        to: String,
        since: Instant,
        pattern: Regex,
        timeout: Duration,
        pollInterval: Duration,
    ): VerificationCode {
        calls += to to MailPurpose.LINK
        linkPatterns += pattern.pattern
        return awaitMatching(to, timeout, pollInterval) { code -> code.link?.let { pattern.containsMatchIn(it.toString()) } == true }
    }

    private suspend fun awaitMatching(
        to: String,
        timeout: Duration,
        pollInterval: Duration,
        usable: (VerificationCode) -> Boolean,
    ): VerificationCode {
        outage?.let { failure ->
            delay(timeout)
            throw failure
        }
        return withTimeoutOrNull(timeout) { next(to.lowercase(), pollInterval, usable) } ?: throw MailTimeoutException(to, timeout)
    }

    private suspend fun next(
        to: String,
        pollInterval: Duration,
        usable: (VerificationCode) -> Boolean,
    ): VerificationCode {
        while (true) {
            take(to, usable)?.let { return it }
            delay(pollInterval)
        }
    }

    private fun take(
        to: String,
        usable: (VerificationCode) -> Boolean,
    ): VerificationCode? =
        synchronized(inbox) {
            val mail = inbox.lastOrNull { mail -> mail.to == to && usable(mail.code) }
            mail?.also { inbox.remove(it) }?.code
        }
}
