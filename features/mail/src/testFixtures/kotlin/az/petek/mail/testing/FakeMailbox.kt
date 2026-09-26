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
