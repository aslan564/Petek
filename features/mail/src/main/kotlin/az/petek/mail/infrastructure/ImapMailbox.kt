/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.infrastructure

import az.petek.mail.domain.MailAddresses
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.MailboxException
import jakarta.mail.MessagingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * [Mailbox] over the owner's IMAP inbox. Every tester has its own address (a `+` address of the owner's box or an
 * address of a catch-all domain), so a message is matched to a tester only by an exact, case-insensitive recipient
 * (`To`, `Cc` or `Delivered-To`): two testers never see each other's mail.
 *
 * Reading never marks a message read ([markRead] does); the blocking IMAP work runs on [io], one conversation with the
 * server at a time. Server and protocol failures become [MailboxException] naming the host, never the password.
 */
class ImapMailbox internal constructor(
    private val settings: ImapSettings,
    private val gateway: ImapGateway,
    private val io: CoroutineDispatcher,
) : Mailbox,
    AutoCloseable {
    constructor(settings: ImapSettings) : this(settings, AngusImapGateway(settings), Dispatchers.IO)

    private val lock = Mutex()

    override suspend fun findLatest(
        to: String,
        since: Instant,
        unreadOnly: Boolean,
    ): MailMessage? = findRecent(to, since, unreadOnly, limit = 1).firstOrNull()

    override suspend fun findRecent(
        to: String,
        since: Instant,
        unreadOnly: Boolean,
        limit: Int,
    ): List<MailMessage> {
        require(limit > 0) { "limit must be positive, was $limit" }
        val address = MailAddresses.normalize(to)
        return call("search for mail to $address") { gateway.candidates(address, since) }
            .filter { candidate -> candidate.message.to.any { MailAddresses.same(it, address) } }
            .filter { !it.message.receivedAt.isBefore(since) && (!unreadOnly || !it.message.read) }
            .sortedByDescending { it.message.receivedAt }
            .take(limit)
            .map { it.message }
    }

    override suspend fun markRead(messageId: String) {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        call("mark message $messageId read") { gateway.markSeen(messageId) }
    }

    override fun close() = gateway.close()

    override fun toString(): String = "ImapMailbox($settings)"

    private suspend fun <T> call(
        action: String,
        block: () -> T,
    ): T =
        lock.withLock {
            withContext(io) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MessagingException) {
                    throw MailboxException("Cannot $action in the IMAP inbox ${settings.username}@${settings.host}: ${e.message}", e)
                } catch (e: java.io.IOException) {
                    throw MailboxException("Cannot $action in the IMAP inbox ${settings.username}@${settings.host}: ${e.message}", e)
                }
            }
        }
}

/** One message the server returned for a search, already read into memory. */
internal data class ImapCandidate(
    val message: MailMessage,
)

/** The blocking IMAP conversation, separated so [ImapMailbox]'s matching is testable without a server. */
internal interface ImapGateway : AutoCloseable {
    /** Messages the server finds for [recipient] received on or after the day of [since] (IMAP dates are days). */
    fun candidates(
        recipient: String,
        since: Instant,
    ): List<ImapCandidate>

    fun markSeen(id: String)
}
