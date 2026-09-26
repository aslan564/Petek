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

package az.petek.faketarget.mail

import java.time.Clock
import java.time.Instant

/** A mailbox with its display name, as Mailpit reports `From`/`To`. */
data class MailAddress(
    val name: String,
    val address: String,
)

/** One e-mail the fake target "sent". Mailpit calls the same thing a message. */
data class SentMail(
    /** Mailpit-style random id (22 alphanumerics). */
    val id: String,
    val messageId: String,
    val from: MailAddress,
    val to: List<MailAddress>,
    val subject: String,
    val text: String,
    val html: String,
    val createdAt: Instant,
    val read: Boolean,
)

/**
 * The fake's outgoing mail: instead of talking SMTP to Mailpit, the fake keeps sent messages here and serves them
 * through its own Mailpit-compatible API. Thread-safe.
 */
class MailOutbox internal constructor(
    private val clock: Clock,
    private val newId: () -> String,
) {
    private val lock = Any()

    /** Oldest first. */
    private val messages = ArrayList<SentMail>()

    /** Newest first, like Mailpit lists them. */
    fun messages(): List<SentMail> = synchronized(lock) { messages.asReversed().toList() }

    internal fun send(
        to: MailAddress,
        subject: String,
        text: String,
        html: String,
    ): SentMail {
        val id = newId()
        val mail =
            SentMail(
                id = id,
                messageId = "$id@fake.kadrohr.local",
                from = SENDER,
                to = listOf(to),
                subject = subject,
                text = text,
                html = html,
                createdAt = clock.instant(),
                read = false,
            )
        synchronized(lock) { messages += mail }
        return mail
    }

    internal fun find(id: String): SentMail? = synchronized(lock) { messages.firstOrNull { it.id == id } }

    internal fun latest(): SentMail? = synchronized(lock) { messages.lastOrNull() }

    /** Marks [ids] (all messages when empty) as read or unread; returns how many matched. */
    internal fun setRead(
        ids: Collection<String>,
        read: Boolean,
    ): Int =
        synchronized(lock) {
            var matched = 0
            messages.replaceAll { mail ->
                if (ids.isEmpty() || mail.id in ids) {
                    matched++
                    mail.copy(read = read)
                } else {
                    mail
                }
            }
            matched
        }

    /** Deletes [ids] (everything when empty); returns how many were removed. */
    internal fun delete(ids: Collection<String>): Int =
        synchronized(lock) {
            val before = messages.size
            if (ids.isEmpty()) messages.clear() else messages.removeIf { it.id in ids }
            before - messages.size
        }

    private companion object {
        val SENDER = MailAddress("KadroHR", "no-reply@fake.kadrohr.local")
    }
}
