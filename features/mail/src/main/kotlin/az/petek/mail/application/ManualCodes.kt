/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.application

import az.petek.mail.domain.MailAddresses
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.VerificationCode
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The owner as the inbox (`PETEK_MAIL_SOURCE=manual`, Faza 10): when a tester waits for a code, the desk lists the
 * request; the owner reads the code on their own phone or mailbox and types it into the panel ("Kodu daxil et"); the
 * tester's next look at [ManualCodeMailbox] finds it. Meant for the explorer's one to three sessions, not a swarm.
 *
 * Codes are never logged. Thread-safe.
 */
class ManualCodeDesk(
    private val now: () -> Instant = Instant::now,
) {
    /** A tester waiting for a code sent to [to]. */
    data class Request(
        val id: String,
        val to: String,
        val askedAt: Instant,
    )

    private data class Answer(
        val messageId: String,
        val code: String,
        val at: Instant,
        val read: Boolean = false,
    )

    private val lock = Any()
    private val waiting = LinkedHashMap<String, Request>()
    private val answers = HashMap<String, Answer>()
    private val sequence = AtomicLong()

    /** Registers that a tester waits for mail to [to] (once per address until it is answered). */
    fun ask(to: String): Request {
        val address = MailAddresses.normalize(to)
        return synchronized(lock) {
            waiting.getOrPut(address) { Request("m${sequence.incrementAndGet()}", address, now()) }
        }
    }

    /** Requests the owner has not answered yet, oldest first. */
    fun pending(): List<Request> = synchronized(lock) { waiting.values.toList() }

    /** Gives [code] to request [id]; false when there is no such waiting request or the code is not a plain code. */
    fun answer(
        id: String,
        code: String,
    ): Boolean {
        val trimmed = code.trim()
        if (!CODE.matches(trimmed)) return false
        return synchronized(lock) {
            val request = waiting.values.firstOrNull { it.id == id } ?: return false
            waiting.remove(request.to)
            answers[request.to] = Answer("manual-${request.id}", trimmed, now())
            true
        }
    }

    /** The owner's answer for [to] as a message, or null while none was typed. */
    fun message(to: String): MailMessage? {
        val address = MailAddresses.normalize(to)
        val answer = synchronized(lock) { answers[address] } ?: return null
        return MailMessage(
            id = answer.messageId,
            to = listOf(address),
            subject = SUBJECT,
            receivedAt = answer.at,
            text = answer.code,
            html = null,
            read = answer.read,
        )
    }

    fun markRead(messageId: String) {
        synchronized(lock) {
            answers.entries.firstOrNull { it.value.messageId == messageId }?.let { it.setValue(it.value.copy(read = true)) }
        }
    }

    companion object {
        const val SUBJECT = "Kod (sahib daxil etdi)"

        /** A verification code or short token: 3–16 letters, digits or '-'. */
        private val CODE = Regex("[A-Za-z0-9-]{3,16}")
    }
}

/** [Mailbox] whose messages are the codes the owner types at the [desk]; asking for mail registers a request there. */
class ManualCodeMailbox(
    private val desk: ManualCodeDesk,
) : Mailbox {
    override suspend fun findLatest(
        to: String,
        since: Instant,
        unreadOnly: Boolean,
    ): MailMessage? {
        val message = desk.message(to)?.takeIf { !it.receivedAt.isBefore(since) && (!unreadOnly || !it.read) }
        if (message == null) desk.ask(to)
        return message
    }

    override suspend fun markRead(messageId: String) = desk.markRead(messageId)

    override fun toString(): String = "ManualCodeMailbox"
}

/**
 * Gives a human time to type the code: every wait lasts at least [minimum] (a person reading an SMS needs minutes, a
 * mail server seconds). Used with [ManualCodeMailbox].
 */
class PatientVerification(
    private val base: AwaitVerificationUseCase,
    private val minimum: Duration = MANUAL_MINIMUM,
) : AwaitVerificationUseCase {
    override suspend fun await(
        to: String,
        since: Instant,
        purpose: MailPurpose,
        timeout: Duration,
        pollInterval: Duration,
    ): VerificationCode = base.await(to, since, purpose, maxOf(timeout, minimum), pollInterval)

    override suspend fun awaitLink(
        to: String,
        since: Instant,
        pattern: Regex,
        timeout: Duration,
        pollInterval: Duration,
    ): VerificationCode = base.awaitLink(to, since, pattern, maxOf(timeout, minimum), pollInterval)

    companion object {
        val MANUAL_MINIMUM: Duration = 5.minutes
    }
}
