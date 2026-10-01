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

import az.petek.mail.domain.MailAddresses
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.MailboxException
import az.petek.mail.domain.UnreadableMailException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.MessagingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * [Mailbox] over the owner's IMAP inbox. Every tester has its own address (a `+` address of the owner's box or an
 * address of a catch-all domain), so a message is matched to a tester only by an exact, case-insensitive recipient
 * (`To`, `Cc` or `Delivered-To`): two testers never see each other's mail.
 *
 * Many testers share the one inbox (30 to 100 of them may wait for a code at the same moment), and an IMAP
 * conversation with a remote server takes most of a second. So the requests are not served one conversation each, one
 * tester after another: one worker serves them in rounds, and a round answers every request that came in since the
 * previous round, all "mark read"s in one conversation ([ImapGateway.markSeen]) and all searches in one more
 * ([ImapGateway.find]). A tester waits at most for the round in progress and its own, however many testers wait with it.
 *
 * Reading never marks a message read ([markRead] does). Server and protocol failures become [MailboxException]
 * naming the host and the tester's own action, never the password; a failed round fails only the requests in it, and
 * the next round starts afresh. A tester's message that is there but cannot be read fails only that tester's search,
 * with [UnreadableMailException] naming it and carrying the messages that could be read. A caller that stops waiting
 * (its time is up) is left out of the rounds not yet started.
 */
class ImapMailbox internal constructor(
    private val settings: ImapSettings,
    private val gateway: ImapGateway,
    scope: CoroutineScope,
) : Mailbox,
    AutoCloseable {
    constructor(settings: ImapSettings) : this(
        settings,
        AngusImapGateway(settings),
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("imap-inbox")),
    )

    private val requests = Channel<Request<*>>(Channel.UNLIMITED)
    private val worker: Job = scope.launch { serve() }

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
        val query = ImapQuery.of(to, since, unreadOnly, limit)
        val found = ask(Search(query))
        // The gateway already answers by these rules; they are applied here again so that no gateway, however it
        // searches, can hand one tester another tester's mail.
        val messages =
            found.messages
                .filter(query::accepts)
                .sortedWith(NEWEST_FIRST)
                .take(limit)
        if (found.unreadable.isNotEmpty()) throw unreadable(query, found.unreadable, messages)
        return messages
    }

    override suspend fun markRead(messageId: String) {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        ask(MarkSeen(messageId))
    }

    override fun close() {
        requests.close()
        worker.cancel()
        gateway.close()
    }

    override fun toString(): String = "ImapMailbox($settings)"

    private suspend fun <T> ask(request: Request<T>): T {
        if (requests.trySend(request).isFailure) throw closed(request)
        try {
            return request.reply.await()
        } catch (e: CancellationException) {
            // The caller stopped waiting: a round that has not started yet leaves this request out.
            request.reply.cancel()
            throw e
        }
    }

    private suspend fun serve() {
        val round = mutableListOf<Request<*>>()
        try {
            for (first in requests) {
                round += first
                while (true) round += requests.tryReceive().getOrNull() ?: break
                serve(round.filter { it.waiting })
                round.clear()
            }
        } finally {
            // Closed (or broken): nobody may be left waiting for an answer that will not come.
            requests.close()
            round.forEach { it.fail(closed(it)) }
            while (true) requests.tryReceive().getOrNull()?.let { it.fail(closed(it)) } ?: break
        }
    }

    /** One round: the marks first, so a search of the same round already sees them, then every search at once. */
    private suspend fun serve(round: List<Request<*>>) {
        val marks = round.filterIsInstance<MarkSeen>()
        if (marks.isNotEmpty()) {
            answer(marks) {
                gateway.markSeen(marks.map { it.id }.distinct())
                marks.forEach { it.reply.complete(Unit) }
            }
        }
        val searches = round.filterIsInstance<Search>()
        if (searches.isNotEmpty()) {
            answer(searches) {
                val found = gateway.find(searches.map { it.query })
                check(found.size == searches.size) { "the IMAP gateway answered ${found.size} of ${searches.size} searches" }
                searches.zip(found).forEach { (search, messages) -> search.reply.complete(messages) }
            }
        }
    }

    private suspend fun answer(
        round: List<Request<*>>,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MessagingException) {
            round.forEach { it.fail(failure(it, e)) }
        } catch (e: IOException) {
            round.forEach { it.fail(failure(it, e)) }
        } catch (e: Exception) {
            // Not a mail failure but a defect: every waiter of the round learns it rather than waiting forever.
            logger.warn(e) { "An IMAP round of ${round.size} request(s) failed unexpectedly" }
            round.forEach { it.fail(e) }
        }
    }

    private fun failure(
        request: Request<*>,
        e: Exception,
    ) = MailboxException("Cannot ${request.action} in the IMAP inbox ${settings.username}@${settings.host}: ${e.message}", e)

    private fun unreadable(
        query: ImapQuery,
        unreadable: Map<Long, String>,
        readable: List<MailMessage>,
    ): UnreadableMailException {
        val which = unreadable.entries.joinToString { (uid, why) -> "$uid ($why)" }
        val noun = if (unreadable.size == 1) "message" else "messages"
        return UnreadableMailException(
            "Mail to ${query.recipient} is in the IMAP inbox ${settings.username}@${settings.host} but cannot be read: $noun $which",
            readable,
        )
    }

    private fun closed(request: Request<*>) =
        MailboxException("Cannot ${request.action}: the IMAP inbox ${settings.username}@${settings.host} is closed")

    /** One tester's request, answered by a round of the worker. */
    private sealed class Request<T>(
        val action: String,
    ) {
        val reply = CompletableDeferred<T>()

        /** False once answered, or once its caller stopped waiting. */
        val waiting: Boolean get() = !reply.isCompleted

        fun fail(e: Throwable) {
            reply.completeExceptionally(e)
        }
    }

    private class Search(
        val query: ImapQuery,
    ) : Request<ImapFound>("search for mail to ${query.recipient}")

    private class MarkSeen(
        val id: String,
    ) : Request<Unit>("mark message $id read")

    private companion object {
        /** Newest first; within one second (all an IMAP server keeps of an arrival) the higher UID arrived later. */
        val NEWEST_FIRST: Comparator<MailMessage> =
            compareByDescending<MailMessage> { it.receivedAt }.thenByDescending { it.id.toLongOrNull() ?: Long.MIN_VALUE }
    }
}

/**
 * What one tester asks the inbox: mail to [recipient] (normalized) received at or after [from], unread only when
 * [unreadOnly], newest first and at most [limit].
 */
internal data class ImapQuery(
    val recipient: String,
    val from: Instant,
    val unreadOnly: Boolean,
    val limit: Int,
) {
    init {
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    /** Whether a message with these [recipients], arrival and read state answers this query. */
    fun accepts(
        recipients: List<String>,
        receivedAt: Instant,
        read: Boolean,
    ): Boolean = recipients.any { MailAddresses.same(it, recipient) } && !receivedAt.isBefore(from) && (!unreadOnly || !read)

    fun accepts(message: MailMessage): Boolean = accepts(message.to, message.receivedAt, message.read)

    companion object {
        /**
         * The query of a [Mailbox.findRecent] call. The server keeps a message's arrival only to the second (IMAP
         * INTERNALDATE): a code that came in the same second as [since] is read as that whole second and would
         * otherwise look older than the wait for it, so [from] is [since] to the second.
         */
        fun of(
            to: String,
            since: Instant,
            unreadOnly: Boolean,
            limit: Int,
        ) = ImapQuery(MailAddresses.normalize(to), since.truncatedTo(ChronoUnit.SECONDS), unreadOnly, limit)
    }
}

/**
 * A gateway's answer to one [ImapQuery]: its [messages], read in full, and those of its messages that are there but
 * could not be read, by UID with why ([unreadable]); never left out silently, as the site did send them.
 */
internal data class ImapFound(
    val messages: List<MailMessage>,
    val unreadable: Map<Long, String> = emptyMap(),
)

/** The IMAP conversations, separated so [ImapMailbox]'s rounds and matching are testable without a server. */
internal interface ImapGateway : AutoCloseable {
    /**
     * One conversation answering every query: for each, in the order given, its messages by the [ImapQuery] rules
     * (exact recipient, arrival, read state), newest first, at most its limit, read in full ([ImapFound]).
     */
    suspend fun find(queries: List<ImapQuery>): List<ImapFound>

    /** One conversation marking every message of [ids] (UIDs) seen; ids the server does not know are ignored. */
    suspend fun markSeen(ids: Collection<String>)
}
