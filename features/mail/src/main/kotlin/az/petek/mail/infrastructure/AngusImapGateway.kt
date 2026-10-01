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
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Address
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderClosedException
import jakarta.mail.Message
import jakarta.mail.MessageRemovedException
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.StoreClosedException
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.AndTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.HeaderTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * The IMAP conversations over Jakarta Mail (Eclipse Angus). One store connection is kept and reopened when the server
 * dropped it; the folder is opened read-only for searches (reading never sets `\Seen`) and read-write only to mark
 * messages seen. Messages are identified by their UID, which stays valid across connections.
 *
 * One [find] answers a whole round of testers with a handful of commands, whatever their number: the server is
 * searched for all their addresses at once ([RECIPIENTS_PER_SEARCH] to a command), the envelopes of what it found
 * (recipients, arrival, `\Seen`) come in one fetch, each tester's messages are told apart by the exact address before
 * any limit applies, and only the chosen messages are downloaded, in one more fetch, and only once: a message's content
 * never changes under its UID, so it is kept ([MessageCache]) for the next rounds. A message that cannot be read (a
 * broken MIME part, an unknown charset) is skipped and logged, so it never fails the other testers' round.
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
    private val bodies = MessageCache()
    private val reportedUnreadable = HashSet<Long>()

    private fun isLoopback(host: String): Boolean = host.lowercase() in setOf("localhost", "127.0.0.1", "::1") || host.startsWith("127.")

    override suspend fun find(queries: List<ImapQuery>): List<List<MailMessage>> {
        if (queries.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) { withFolder(Folder.READ_ONLY) { folder -> find(folder, queries) } }
    }

    override suspend fun markSeen(ids: Collection<String>) {
        val uids = ids.mapNotNull { it.toLongOrNull() }.distinct().toLongArray()
        if (uids.isEmpty()) return
        withContext(Dispatchers.IO) {
            withFolder(Folder.READ_WRITE) { folder ->
                val messages = (folder as UIDFolder).getMessagesByUID(uids).filterNotNull().toTypedArray()
                if (messages.isNotEmpty()) folder.setFlags(messages, Flags(Flags.Flag.SEEN), true)
            }
        }
    }

    override fun close() {
        synchronized(this) {
            store?.takeIf { it.isConnected }?.close()
            store = null
        }
    }

    private fun find(
        folder: Folder,
        queries: List<ImapQuery>,
    ): List<List<MailMessage>> {
        val uids = folder as UIDFolder
        if (bodies.keepFor(uids.uidValidity)) reportedUnreadable.clear()
        // IMAP dates are days in the server's zone: a day earlier covers any zone.
        val day = Date.from(queries.minOf { it.from }.truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS))
        val found =
            queries
                .map { it.recipient }
                .distinct()
                .chunked(RECIPIENTS_PER_SEARCH)
                .flatMap { chunk ->
                    folder.search(AndTerm(ReceivedDateTerm(ComparisonTerm.GE, day), ImapSearch.addressedToAny(chunk))).asList()
                }.distinctBy { it.messageNumber }
                .sortedBy { it.messageNumber }
                .takeLast(MAX_SCANNED)
                .toTypedArray()
        if (found.isEmpty()) return queries.map { emptyList() }
        folder.fetch(found, ENVELOPES)
        val messages = HashMap<Long, Message>()
        val envelopes =
            found.mapNotNull { message ->
                val uid =
                    try {
                        uids.getUID(message)
                    } catch (_: MessageRemovedException) {
                        return@mapNotNull null
                    }
                messages[uid] = message
                readable(
                    uid,
                ) { ImapEnvelope(uid, MimeMail.recipients(message), MimeMail.receivedAt(message), message.isSet(Flags.Flag.SEEN)) }
            }
        val selected = ImapSelection.select(queries, envelopes)
        val wanted = selected.flatten().distinct()
        val chosen = HashMap<Long, MailMessage>()
        wanted.forEach { uid -> bodies[uid]?.let { chosen[uid] = it } }
        chosen += download(folder, wanted.filter { it !in chosen }.map { it to messages.getValue(it) })
        val read = envelopes.associate { it.uid to it.read }
        return selected.map { uidsOfQuery -> uidsOfQuery.mapNotNull { uid -> chosen[uid]?.copy(read = read.getValue(uid)) } }
    }

    /** Reads [wanted] in full with one fetch and keeps them for later rounds. */
    private fun download(
        folder: Folder,
        wanted: List<Pair<Long, Message>>,
    ): Map<Long, MailMessage> {
        if (wanted.isEmpty()) return emptyMap()
        folder.fetch(wanted.map { it.second }.toTypedArray(), WHOLE_MESSAGES)
        return wanted
            .mapNotNull { (uid, message) -> readable(uid) { MimeMail.read(uid.toString(), message) }?.let { uid to it } }
            .toMap()
            .onEach { (uid, message) -> bodies.put(uid, message) }
    }

    /**
     * [block]'s reading of message [uid], or null when the message is gone or cannot be read (logged once); a lost
     * connection still fails the round, as it fails every message of it.
     */
    private fun <T : Any> readable(
        uid: Long,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: FolderClosedException) {
            throw e
        } catch (e: StoreClosedException) {
            throw e
        } catch (_: MessageRemovedException) {
            null
        } catch (e: MessagingException) {
            unreadable(uid, e)
        } catch (e: IOException) {
            unreadable(uid, e)
        }

    private fun unreadable(
        uid: Long,
        e: Exception,
    ): Nothing? {
        if (reportedUnreadable.add(uid)) logger.warn { "Skipping IMAP message $uid: it cannot be read (${e.message})" }
        return null
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

    companion object {
        /** Addresses searched by one command: four terms each keep it far below any server's command length limit. */
        const val RECIPIENTS_PER_SEARCH = 16

        /** A test inbox may hold years of mail: of what a round's searches find, only the newest are looked at. */
        const val MAX_SCANNED = 1_000

        /** What tells whose a message is: recipients, delivery headers, arrival, `\Seen` and UID, without the body. */
        private val ENVELOPES =
            FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
                add(ImapSearch.DELIVERED_TO)
                add(ImapSearch.ORIGINAL_TO)
            }

        /** The whole message (`BODY.PEEK[]`), read locally afterwards. */
        private val WHOLE_MESSAGES = FetchProfile().apply { add(IMAPFolder.FetchProfileItem.MESSAGE) }
    }
}

/** A message as a search first sees it: enough to tell whose it is before its body is read. */
internal data class ImapEnvelope(
    val uid: Long,
    val recipients: List<String>,
    val receivedAt: Instant,
    val read: Boolean,
)

/** Which of a round's messages answer which tester. Pure. */
internal object ImapSelection {
    /**
     * For each query, in order, the UIDs of its messages among [envelopes] by the [ImapQuery] rules, newest first
     * (within one second, the higher UID arrived later) and at most its limit. The limit applies only after the exact
     * recipient: other testers' mail, however much newer, never pushes a tester's own message out.
     */
    fun select(
        queries: List<ImapQuery>,
        envelopes: List<ImapEnvelope>,
    ): List<List<Long>> {
        val newestFirst = envelopes.sortedWith(compareByDescending<ImapEnvelope> { it.receivedAt }.thenByDescending { it.uid })
        return queries.map { query ->
            newestFirst
                .asSequence()
                .filter { query.accepts(it.recipients, it.receivedAt, it.read) }
                .take(query.limit)
                .map { it.uid }
                .toList()
        }
    }
}

/**
 * Messages already read, by UID: a message's content never changes under its UID while the folder keeps its
 * UIDVALIDITY ([keepFor] forgets everything when it changes). Bounded by [maxChars] of text and HTML; the least
 * recently used go first. The read state is not part of what is kept: it is read afresh every round. Not thread-safe.
 */
internal class MessageCache(
    private val maxChars: Long = DEFAULT_MAX_CHARS,
) {
    private val entries = LinkedHashMap<Long, MailMessage>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var validity: Long? = null
    private var chars = 0L

    val size: Int get() = entries.size

    /** Keeps what is cached only for the folder's [uidValidity]; true when it changed and everything was forgotten. */
    fun keepFor(uidValidity: Long): Boolean {
        if (validity == uidValidity) return false
        val changed = validity != null
        entries.clear()
        chars = 0
        validity = uidValidity
        return changed
    }

    operator fun get(uid: Long): MailMessage? = entries[uid]

    fun put(
        uid: Long,
        message: MailMessage,
    ) {
        entries.put(uid, message)?.let { chars -= weight(it) }
        chars += weight(message)
        val eldest = entries.values.iterator()
        while (chars > maxChars && entries.size > 1) {
            chars -= weight(eldest.next())
            eldest.remove()
        }
    }

    private fun weight(message: MailMessage): Long = message.text.length.toLong() + (message.html?.length ?: 0)

    companion object {
        /** About 16 MB: hundreds of verification mails, more than one round of 100 testers reads. */
        const val DEFAULT_MAX_CHARS = 8_000_000L
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}

/** What the server is asked for a tester's mail. Pure: the terms match messages locally too, so it is tested without a server. */
internal object ImapSearch {
    /**
     * Mail addressed to [recipient] as the server sees it: in To or Cc, or only in the headers a catch-all inbox or a Bcc
     * leaves behind (`Delivered-To`, `X-Original-To`), which a To/Cc search alone never finds.
     */
    fun addressedTo(recipient: String): SearchTerm = addressedToAny(listOf(recipient))

    /** Mail [addressedTo] any of [recipients]: one search for a round of testers. */
    fun addressedToAny(recipients: Collection<String>): SearchTerm {
        require(recipients.isNotEmpty()) { "no recipients to search for" }
        return OrTerm(
            recipients
                .flatMap { recipient ->
                    listOf(
                        RecipientStringTerm(Message.RecipientType.TO, recipient),
                        RecipientStringTerm(Message.RecipientType.CC, recipient),
                        HeaderTerm(DELIVERED_TO, recipient),
                        HeaderTerm(ORIGINAL_TO, recipient),
                    )
                }.toTypedArray(),
        )
    }

    const val DELIVERED_TO = "Delivered-To"
    const val ORIGINAL_TO = "X-Original-To"
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
            receivedAt = receivedAt(message),
            text = bodies.text ?: html?.let(::textOf).orEmpty(),
            html = html,
            read = message.isSet(Flags.Flag.SEEN),
        )
    }

    /** When the server received [message] (IMAP INTERNALDATE, to the second), else when it was sent. */
    fun receivedAt(message: Message): Instant = (message.receivedDate ?: message.sentDate)?.toInstant() ?: Instant.EPOCH

    /** Every address [message] names its recipient by: To, Cc and the delivery headers a catch-all or a Bcc leaves. */
    fun recipients(message: Message): List<String> {
        val listed: List<Address> =
            message.getRecipients(Message.RecipientType.TO).orEmpty().toList() +
                message.getRecipients(Message.RecipientType.CC).orEmpty().toList()
        val delivered: List<String> =
            listOf(ImapSearch.DELIVERED_TO, ImapSearch.ORIGINAL_TO).flatMap { header ->
                message.getHeader(header)?.map { it.trim() }.orEmpty()
            }
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
