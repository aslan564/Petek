/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.infrastructure

import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.MailboxException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * [Mailbox] over Mailpit's REST API v1 (https://mailpit.axllent.org/docs/api-v1/), the catch-all inbox that the
 * target's test mode sends to. Construct it with Mailpit's web address (`PETEK_MAILPIT_URL`, e.g.
 * `http://localhost:8025`); a path in it (Mailpit's `MP_WEBROOT`) is kept. Basic auth (`MP_UI_AUTH`) is not
 * supported: credentials in the URL are ignored and never appear in messages.
 *
 * - Search: `GET api/v1/search?query=to:"<address>"`. Mailpit's `to:` is a substring match, so the recipient is
 *   compared exactly (case-insensitive) here, as are `since` (against `Created`, the receive time) and the read flag.
 * - Bodies: `GET api/v1/message/{ID}`, which in Mailpit also marks the message read. Unread messages that were only
 *   looked at are set back to unread, so reading stays free of side effects as [Mailbox.findRecent] promises.
 * - Read state: `PUT api/v1/messages` with `{"IDs":[…],"Read":…}`. Mailpit-compatible stand-ins that only offer
 *   `PUT api/v1/read` (Pətək's fake target) are detected once by a 404/405 answer and used from then on.
 *
 * Transport failures, timeouts, unexpected statuses and malformed JSON become [MailboxException] with the action and
 * the Mailpit address in the message. The instance owns (and [close]s) its HTTP client unless one is injected; an
 * injected client keeps its own timeout and redirect settings.
 */
class MailpitMailbox(
    baseUrl: URI,
    client: HttpClient? = null,
    requestTimeout: Duration = 10.seconds,
) : Mailbox,
    AutoCloseable {
    private val apiRoot: String = apiRootOf(baseUrl)
    private val ownsClient = client == null
    private val http: HttpClient = client ?: defaultClient(requestTimeout)
    private val readStatusPath = AtomicReference<String?>(null)
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    init {
        if (baseUrl.rawUserInfo != null) logger.warn { "Credentials in the Mailpit URL are ignored (basic auth is not supported)" }
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
    ): List<MailMessage> {
        require(limit > 0) { "limit must be positive, was $limit" }
        val address = recipientAddress(to)
        val matching =
            search(address)
                .asSequence()
                .filter { it.isAddressedTo(address) && (!unreadOnly || !it.read) }
                .mapNotNull(::received)
                .filter { !it.at.isBefore(since) }
                .sortedByDescending { it.at }
                .take(limit)
                .toList()
        return readBodies(matching)
    }

    override suspend fun markRead(messageId: String) {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        setRead(listOf(messageId), read = true)
    }

    override fun close() {
        if (ownsClient) http.close()
    }

    override fun toString(): String = "MailpitMailbox($apiRoot)"

    private suspend fun search(address: String): List<MailpitSummary> {
        val action = "search for mail to $address"
        val url =
            endpoint("search").apply {
                parameters.append("query", "to:\"$address\"")
                parameters.append("limit", SEARCH_LIMIT.toString())
            }
        val reply =
            exchange(action) {
                http.get(url.build()) {
                    expectSuccess = false
                    accept(ContentType.Application.Json)
                }
            }.requireSuccess(action)
        return reply.decode(MailpitSearchResult.serializer(), action).messages.orEmpty()
    }

    private suspend fun readBodies(matching: List<Received>): List<MailMessage> {
        val lookedAtUnread = mutableListOf<String>()
        try {
            return matching.mapNotNull { received ->
                if (!received.summary.read) lookedAtUnread += received.summary.id
                fetch(received)
            }
        } finally {
            if (lookedAtUnread.isNotEmpty()) withContext(NonCancellable) { restoreUnread(lookedAtUnread) }
        }
    }

    /** Null when the message disappeared between search and fetch (deleted in the Mailpit UI, for example). */
    private suspend fun fetch(received: Received): MailMessage? {
        val summary = received.summary
        val action = "read message ${summary.id}"
        val reply =
            exchange(action) {
                http.get(endpoint("message", summary.id).build()) {
                    expectSuccess = false
                    accept(ContentType.Application.Json)
                }
            }
        if (reply.status == HttpStatusCode.NotFound) return null
        val message = reply.requireSuccess(action).decode(MailpitMessage.serializer(), action)
        return MailMessage(
            id = summary.id,
            to = addresses(message.to).ifEmpty { addresses(summary.to) },
            subject = message.subject ?: summary.subject.orEmpty(),
            receivedAt = received.at,
            text = message.text.orEmpty(),
            html = message.html?.takeIf { it.isNotBlank() },
            read = summary.read,
        )
    }

    /** Best effort: a failure here must not hide the messages already read, so it is logged rather than thrown. */
    private suspend fun restoreUnread(ids: List<String>) {
        try {
            setRead(ids, read = false)
        } catch (e: MailboxException) {
            logger.warn { "Could not restore the unread state of ${ids.size} Mailpit message(s): ${e.message}" }
        }
    }

    private suspend fun setRead(
        ids: List<String>,
        read: Boolean,
    ) {
        // An empty ID list would make Mailpit apply the change to every message in the inbox.
        check(ids.isNotEmpty()) { "Refusing to change the read state without message IDs" }
        val action = if (read) "mark ${ids.joinToString()} read" else "restore unread state of ${ids.joinToString()}"
        val body = json.encodeToString(MailpitReadStatus.serializer(), MailpitReadStatus(ids, read))
        val candidates = readStatusPath.get()?.let(::listOf) ?: READ_STATUS_PATHS
        for ((index, path) in candidates.withIndex()) {
            val reply =
                exchange(action) {
                    http.put(endpoint(path).build()) {
                        expectSuccess = false
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                }
            if (index < candidates.lastIndex && reply.status in ENDPOINT_MISSING) continue
            reply.requireSuccess(action)
            readStatusPath.set(path)
            return
        }
    }

    private fun endpoint(vararg segments: String): URLBuilder =
        URLBuilder(apiRoot).appendPathSegments(segments.toList(), encodeSlash = true)

    private suspend fun exchange(
        action: String,
        request: suspend () -> HttpResponse,
    ): Reply =
        try {
            val response = request()
            Reply(response.status, response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MailboxException("Mailpit at $apiRoot: $action failed (${e::class.simpleName}: ${e.message})", e)
        }

    private fun Reply.requireSuccess(action: String): Reply {
        if (!status.isSuccess()) {
            throw MailboxException("Mailpit at $apiRoot: $action answered HTTP ${status.value}: ${snippet(body)}")
        }
        return this
    }

    private fun <T> Reply.decode(
        serializer: KSerializer<T>,
        action: String,
    ): T =
        try {
            json.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            throw MailboxException("Mailpit at $apiRoot: $action returned unexpected JSON (${e.message})", e)
        } catch (e: IllegalArgumentException) {
            throw MailboxException("Mailpit at $apiRoot: $action returned unexpected JSON (${e.message})", e)
        }

    private fun received(summary: MailpitSummary): Received? {
        val at = parseInstant(summary.created)
        if (at == null) logger.warn { "Ignoring Mailpit message ${summary.id}: unreadable Created '${summary.created}'" }
        return at?.let { Received(summary, it) }
    }

    private fun MailpitSummary.isAddressedTo(address: String): Boolean = addresses(to).any { it.equals(address, ignoreCase = true) }

    private class Received(
        val summary: MailpitSummary,
        val at: Instant,
    )

    private class Reply(
        val status: HttpStatusCode,
        val body: String,
    )

    private companion object {
        /** Newest first; far more than one tester's inbox ever holds during a run. */
        const val SEARCH_LIMIT = 50
        const val SNIPPET_LENGTH = 200
        val READ_STATUS_PATHS = listOf("messages", "read")
        val ENDPOINT_MISSING = setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)

        /** Scheme, host, port and web root of [baseUrl] plus `/api/v1`; user info is dropped so it never reaches a message. */
        fun apiRootOf(baseUrl: URI): String {
            require(baseUrl.scheme?.lowercase() in setOf("http", "https") && !baseUrl.host.isNullOrEmpty()) {
                "Mailpit URL must be an absolute http(s) URL, was ${withoutUserInfo(baseUrl)}"
            }
            val hostAndPort = baseUrl.rawAuthority.substringAfterLast('@')
            return "${baseUrl.scheme}://$hostAndPort${baseUrl.rawPath.orEmpty().trimEnd('/')}/api/v1"
        }

        fun withoutUserInfo(url: URI): String = url.rawUserInfo?.let { url.toString().replace("$it@", "***@") } ?: url.toString()

        fun defaultClient(requestTimeout: Duration): HttpClient {
            require(requestTimeout.isPositive()) { "requestTimeout must be positive, was $requestTimeout" }
            return HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = requestTimeout.inWholeMilliseconds
                    connectTimeoutMillis = minOf(requestTimeout, 5.seconds).inWholeMilliseconds
                    socketTimeoutMillis = requestTimeout.inWholeMilliseconds
                }
            }
        }

        fun recipientAddress(to: String): String {
            val address = to.trim()
            require(address.isNotEmpty() && address.none { it.isWhitespace() || it == '"' }) {
                "Recipient must be a plain e-mail address, was '$to'"
            }
            return address
        }

        fun addresses(list: List<MailpitAddress>?): List<String> =
            list.orEmpty().mapNotNull { it.address?.trim()?.takeIf(String::isNotEmpty) }

        /** Go marshals times as RFC 3339 with an offset (`2026-09-25T12:00:00.123+04:00`). */
        fun parseInstant(value: String?): Instant? =
            value?.let {
                try {
                    OffsetDateTime.parse(it).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }

        fun snippet(body: String): String =
            body
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(SNIPPET_LENGTH)
                .ifEmpty { "(empty body)" }
    }
}
