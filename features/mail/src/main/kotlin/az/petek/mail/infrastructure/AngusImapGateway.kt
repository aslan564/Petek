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

import az.petek.mail.domain.MailMessage
import jakarta.mail.Address
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.AndTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Properties

/**
 * The IMAP conversation over Jakarta Mail (Eclipse Angus). One store connection is kept and reopened when the server
 * dropped it; the folder is opened read-only for searches (reading never sets `\Seen`) and read-write only to mark
 * a message seen. Messages are identified by their UID, which stays valid across connections.
 */
internal class AngusImapGateway(
    private val settings: ImapSettings,
) : ImapGateway {
    private val protocol = if (settings.tls) "imaps" else "imap"
    private val session: Session =
        Session.getInstance(
            Properties().apply {
                val timeout = settings.timeout.inWholeMilliseconds.toString()
                put("mail.$protocol.host", settings.host)
                put("mail.$protocol.port", settings.port.toString())
                put("mail.$protocol.connectiontimeout", timeout)
                put("mail.$protocol.timeout", timeout)
                put("mail.$protocol.writetimeout", timeout)
                if (!settings.tls) {
                    // Without implicit TLS the connection must still be encrypted (STARTTLS), except to this machine
                    // (a local test IMAP server): the password never crosses a network in clear text.
                    put("mail.imap.starttls.enable", "true")
                    if (!isLoopback(settings.host)) put("mail.imap.starttls.required", "true")
                }
            },
        )
    private var store: Store? = null

    private fun isLoopback(host: String): Boolean = host.lowercase() in setOf("localhost", "127.0.0.1", "::1") || host.startsWith("127.")

    override fun candidates(
        recipient: String,
        since: Instant,
    ): List<ImapCandidate> =
        withFolder(Folder.READ_ONLY) { folder ->
            val day = Date.from(since.truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS))
            val term: SearchTerm =
                AndTerm(
                    ReceivedDateTerm(ComparisonTerm.GE, day),
                    OrTerm(
                        RecipientStringTerm(Message.RecipientType.TO, recipient),
                        RecipientStringTerm(Message.RecipientType.CC, recipient),
                    ),
                )
            val uids = folder as UIDFolder
            folder.search(term).takeLast(MAX_CANDIDATES).map { message ->
                ImapCandidate(MimeMail.read(uids.getUID(message).toString(), message))
            }
        }

    override fun markSeen(id: String) {
        withFolder(Folder.READ_WRITE) { folder ->
            val uid = id.toLongOrNull() ?: return@withFolder
            (folder as UIDFolder).getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, true)
        }
    }

    override fun close() {
        synchronized(this) {
            store?.takeIf { it.isConnected }?.close()
            store = null
        }
    }

    private fun <T> withFolder(
        mode: Int,
        block: (Folder) -> T,
    ): T =
        synchronized(this) {
            val folder = connected().getFolder(settings.folder)
            folder.open(mode)
            try {
                block(folder)
            } finally {
                if (folder.isOpen) folder.close(false)
            }
        }

    private fun connected(): Store {
        store?.takeIf { it.isConnected }?.let { return it }
        return session.getStore(protocol).also {
            it.connect(settings.host, settings.port, settings.username, settings.password.reveal())
            store = it
        }
    }

    private companion object {
        /** A test inbox may hold years of mail; only the newest matches are read. */
        const val MAX_CANDIDATES = 50
    }
}

/** Reads a Jakarta Mail message into a [MailMessage]: recipients, received time, `\Seen`, plain text and HTML. Pure. */
internal object MimeMail {
    fun read(
        id: String,
        message: Message,
    ): MailMessage {
        val bodies = Bodies()
        collect(message, bodies)
        val html = bodies.html
        return MailMessage(
            id = id,
            to = recipients(message),
            subject = message.subject.orEmpty(),
            receivedAt = (message.receivedDate ?: message.sentDate)?.toInstant() ?: Instant.EPOCH,
            text = bodies.text ?: html?.let(::textOf).orEmpty(),
            html = html,
            read = message.isSet(Flags.Flag.SEEN),
        )
    }

    private fun recipients(message: Message): List<String> {
        val listed: List<Address> =
            message.getRecipients(Message.RecipientType.TO).orEmpty().toList() +
                message.getRecipients(Message.RecipientType.CC).orEmpty().toList()
        val delivered: List<String> = message.getHeader("Delivered-To")?.map { it.trim() }.orEmpty()
        return (listed.mapNotNull(::address) + delivered).filter { it.isNotBlank() }.distinct()
    }

    private fun address(address: Address): String? = (address as? InternetAddress)?.address

    private class Bodies {
        var text: String? = null
        var html: String? = null
    }

    private fun collect(
        part: Part,
        bodies: Bodies,
    ) {
        when {
            part.isMimeType("text/plain") && bodies.text == null -> {
                bodies.text = part.content as? String
            }

            part.isMimeType("text/html") && bodies.html == null -> {
                bodies.html = part.content as? String
            }

            part.isMimeType("multipart/*") -> {
                (part.content as? Multipart)?.let { multipart ->
                    (0 until multipart.count).forEach { collect(multipart.getBodyPart(it), bodies) }
                }
            }
        }
    }

    private fun textOf(html: String): String =
        html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
