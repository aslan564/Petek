/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.testing

import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.Mailbox
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class FakeMailbox : Mailbox {
    val messages = CopyOnWriteArrayList<MailMessage>()

    fun deliver(message: MailMessage) {
        messages += message
    }

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
    ): List<MailMessage> =
        messages
            .filter { m -> m.to.any { it.equals(to, ignoreCase = true) } && !m.receivedAt.isBefore(since) && (!unreadOnly || !m.read) }
            .sortedByDescending { it.receivedAt }
            .take(limit)

    override suspend fun markRead(messageId: String) {
        val i = messages.indexOfFirst { it.id == messageId }
        if (i >= 0) messages[i] = messages[i].copy(read = true)
    }
}
