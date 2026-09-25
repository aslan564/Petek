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
import az.petek.mail.domain.MailboxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class MailpitMailboxTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    private fun serve(mailpit: FakeMailpit) = StubHttpServer { mailpit.handle(it) }.closing()

    private fun mailboxFor(
        server: StubHttpServer,
        path: String = "",
    ) = MailpitMailbox(URI("${server.baseUrl}$path")).closing()

    @Test
    fun `finds the newest message to the recipient and reads its bodies`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1", created = "2026-09-25T14:00:01.000+04:00", text = "Kod: 111111"))
        mailpit.add(stored("m2", created = "2026-09-25T14:00:02.500+04:00", text = "Kod: 222222", html = "<p>Kod: 222222</p>"))
        val server = serve(mailpit)

        val message = runBlocking { mailboxFor(server).findLatest(ELI, SINCE) }

        message shouldBe
            MailMessage(
                id = "m2",
                to = listOf(ELI),
                subject = "Təsdiq kodu",
                receivedAt = Instant.parse("2026-09-25T10:00:02.500Z"),
                text = "Kod: 222222",
                html = "<p>Kod: 222222</p>",
                read = false,
            )
        val search = server.requests.first()
        search.method shouldBe "GET"
        search.path shouldBe "/api/v1/search"
        search.query["query"] shouldBe listOf("to:\"$ELI\"")
        search.query["limit"] shouldBe listOf("50")
        search.headers["accept"].shouldNotBeNull() shouldContain "application/json"
        server.requests.map { "${it.method} ${it.path}" } shouldContainExactly
            listOf("GET /api/v1/search", "GET /api/v1/message/m2", "PUT /api/v1/messages")
    }

    @Test
    fun `recipients that merely contain the address are not the recipient`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1", to = listOf("veli.$ELI")))
        mailpit.add(stored("m2", to = listOf("$ELI.evil.example")))
        val mailbox = mailboxFor(serve(mailpit))

        runBlocking { mailbox.findLatest(ELI, SINCE) }.shouldBeNull()
    }

    @Test
    fun `the recipient is compared case-insensitively and may be one of several`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1", to = listOf("someone@test.kadrohr.com", ELI.uppercase())))
        val mailbox = mailboxFor(serve(mailpit))

        runBlocking { mailbox.findLatest(" $ELI ", SINCE) }?.id shouldBe "m1"
    }

    @Test
    fun `messages received before since are ignored and one received exactly at since counts`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("before", created = "2026-09-25T13:59:59.999+04:00"))
        val server = serve(mailpit)
        val mailbox = mailboxFor(server)

        runBlocking { mailbox.findLatest(ELI, SINCE) }.shouldBeNull()
        mailpit.add(stored("exact", created = "2026-09-25T14:00:00+04:00"))
        runBlocking { mailbox.findLatest(ELI, SINCE) }?.id shouldBe "exact"
    }

    @Test
    fun `read messages are skipped only when unreadOnly`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1", read = true))
        val mailbox = mailboxFor(serve(mailpit))

        runBlocking { mailbox.findLatest(ELI, SINCE, unreadOnly = true) }.shouldBeNull()
        runBlocking { mailbox.findLatest(ELI, SINCE, unreadOnly = false) }?.read shouldBe true
    }

    @Test
    fun `looking at an unread message leaves it unread although Mailpit marks fetched messages read`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1"))
        val server = serve(mailpit)

        runBlocking { mailboxFor(server).findLatest(ELI, SINCE) }

        mailpit.byId("m1").read shouldBe false
        val restore = server.requests.last()
        restore.method shouldBe "PUT"
        restore.path shouldBe "/api/v1/messages"
        readStatus(restore.body) shouldBe (listOf("m1") to false)
    }

    @Test
    fun `looking at an already read message does not change its state`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1", read = true))
        val server = serve(mailpit)

        runBlocking { mailboxFor(server).findLatest(ELI, SINCE, unreadOnly = false) }

        mailpit.byId("m1").read shouldBe true
        server.requests.none { it.method == "PUT" } shouldBe true
    }

    @Test
    fun `findRecent returns the newest messages first up to the limit and keeps them unread`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1", created = "2026-09-25T14:00:01+04:00"))
        mailpit.add(stored("m3", created = "2026-09-25T14:00:03+04:00"))
        mailpit.add(stored("m2", created = "2026-09-25T14:00:02+04:00"))
        val server = serve(mailpit)

        val messages = runBlocking { mailboxFor(server).findRecent(ELI, SINCE, limit = 2) }

        messages.map { it.id } shouldContainExactly listOf("m3", "m2")
        mailpit.messages.none { it.read } shouldBe true
        server.requests.count { it.method == "PUT" } shouldBe 1
        readStatus(server.requests.last().body) shouldBe (listOf("m3", "m2") to false)
    }

    @Test
    fun `an empty inbox gives no message after a single search`() {
        val server = serve(FakeMailpit())

        runBlocking { mailboxFor(server).findLatest(ELI, SINCE) }.shouldBeNull()

        server.requests.map { it.path } shouldContainExactly listOf("/api/v1/search")
    }

    @Test
    fun `markRead sends the ID and Read true to Mailpit's read-status endpoint`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1"))
        val server = serve(mailpit)

        runBlocking { mailboxFor(server).markRead("m1") }

        mailpit.byId("m1").read shouldBe true
        val put = server.requests.single()
        put.method shouldBe "PUT"
        put.path shouldBe "/api/v1/messages"
        put.headers["content-type"].shouldNotBeNull() shouldStartWith "application/json"
        readStatus(put.body) shouldBe (listOf("m1") to true)
    }

    @Test
    fun `markRead falls back to the legacy read endpoint once and then uses it directly`() {
        val mailpit = FakeMailpit(readStatusPath = "/api/v1/read")
        mailpit.add(stored("m1"))
        mailpit.add(stored("m2"))
        val server = serve(mailpit)
        val mailbox = mailboxFor(server)

        runBlocking {
            mailbox.markRead("m1")
            mailbox.markRead("m2")
        }

        mailpit.messages.all { it.read } shouldBe true
        server.requests.map { "${it.method} ${it.path}" } shouldContainExactly
            listOf("PUT /api/v1/messages", "PUT /api/v1/read", "PUT /api/v1/read")
    }

    @Test
    fun `a Mailpit web root in the base URL is kept`() {
        val mailpit = FakeMailpit(webroot = "/mailpit")
        mailpit.add(stored("m1"))
        val server = serve(mailpit)
        val mailbox = mailboxFor(server, path = "/mailpit/")

        runBlocking {
            mailbox.findLatest(ELI, SINCE)?.id shouldBe "m1"
            mailbox.markRead("m1")
        }

        server.requests.all { it.path.startsWith("/mailpit/api/v1/") } shouldBe true
        mailpit.byId("m1").read shouldBe true
    }

    @Test
    fun `Go nulls, missing fields and unknown keys are tolerated`() {
        val server =
            StubHttpServer { request ->
                when {
                    request.path == "/api/v1/search" -> {
                        StubResponse(
                            body =
                                """{"total":1,"messages":[{"ID":"x1","Read":null,"Created":"2026-09-25T14:00:01.123456789+04:00",
"To":[{"Name":null,"Address":"$ELI"}],"Subject":null,"Tags":null,"Extra":{"a":[1,2]}}]}
                                """.trimMargin(),
                        )
                    }

                    request.path == "/api/v1/message/x1" -> {
                        StubResponse(body = """{"ID":"x1","To":null,"Subject":"Kod","Text":null,"HTML":"","Inline":null,"Unknown":true}""")
                    }

                    else -> {
                        StubResponse(body = "ok", contentType = "text/plain")
                    }
                }
            }.closing()

        val message = runBlocking { mailboxFor(server).findLatest(ELI, SINCE) }

        message shouldBe
            MailMessage(
                id = "x1",
                to = listOf(ELI),
                subject = "Kod",
                receivedAt = Instant.parse("2026-09-25T10:00:01.123456789Z"),
                text = "",
                html = null,
                read = false,
            )
    }

    @Test
    fun `a search answer without messages is an empty inbox`() {
        val server = StubHttpServer { StubResponse(body = """{"total":0,"messages":null}""") }.closing()

        runBlocking { mailboxFor(server).findRecent(ELI, SINCE) } shouldBe emptyList()
    }

    @Test
    fun `a message with an unreadable receive time is skipped`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("bad", created = "yesterday"))
        val mailbox = mailboxFor(serve(mailpit))

        runBlocking { mailbox.findLatest(ELI, SINCE) }.shouldBeNull()
    }

    @Test
    fun `a message deleted between search and fetch is skipped`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("gone", created = "2026-09-25T14:00:02+04:00"))
        mailpit.add(stored("kept", created = "2026-09-25T14:00:01+04:00"))
        val server =
            StubHttpServer { request ->
                if (request.path == "/api/v1/message/gone") StubResponse(404, "not found", "text/plain") else mailpit.handle(request)
            }.closing()

        runBlocking { mailboxFor(server).findRecent(ELI, SINCE) }.map { it.id } shouldContainExactly listOf("kept")
    }

    @Test
    fun `a failure to restore the unread state does not hide the message`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1"))
        val server =
            StubHttpServer {
                if (it.method ==
                    "PUT"
                ) {
                    StubResponse(500, "db locked", "text/plain")
                } else {
                    mailpit.handle(it)
                }
            }.closing()

        runBlocking { mailboxFor(server).findLatest(ELI, SINCE) }?.id shouldBe "m1"
    }

    @Test
    fun `an HTTP error becomes a MailboxException that names the action and the status`() {
        val server = StubHttpServer { StubResponse(500, "database is locked", "text/plain") }.closing()

        val error = shouldThrow<MailboxException> { runBlocking { mailboxFor(server).findLatest(ELI, SINCE) } }

        error.message.shouldNotBeNull() shouldContain "search for mail to $ELI"
        error.message.shouldNotBeNull() shouldContain "HTTP 500"
        error.message.shouldNotBeNull() shouldContain "database is locked"
        error.message.shouldNotBeNull() shouldContain server.baseUrl.toString()
    }

    @Test
    fun `a failed markRead becomes a MailboxException`() {
        val server = StubHttpServer { StubResponse(400, "bad request", "text/plain") }.closing()

        val error = shouldThrow<MailboxException> { runBlocking { mailboxFor(server).markRead("m1") } }

        error.message.shouldNotBeNull() shouldContain "mark m1 read"
    }

    @Test
    fun `malformed JSON becomes a MailboxException`() {
        val server = StubHttpServer { StubResponse(body = "<html>Mailpit</html>", contentType = "text/html") }.closing()

        val error = shouldThrow<MailboxException> { runBlocking { mailboxFor(server).findLatest(ELI, SINCE) } }

        error.message.shouldNotBeNull() shouldContain "unexpected JSON"
    }

    @Test
    fun `an unreachable Mailpit becomes a MailboxException with the cause`() {
        val stopped = StubHttpServer { StubResponse() }
        val url = stopped.baseUrl
        stopped.close()

        val error = shouldThrow<MailboxException> { runBlocking { MailpitMailbox(url).closing().findLatest(ELI, SINCE) } }

        error.cause.shouldNotBeNull()
        error.message.shouldNotBeNull() shouldContain url.toString()
    }

    @Test
    fun `a hanging Mailpit times out as a MailboxException`() {
        val release = CompletableDeferred<Unit>()
        val server =
            StubHttpServer {
                release.await()
                StubResponse()
            }.closing()
        try {
            val mailbox = MailpitMailbox(server.baseUrl, requestTimeout = 300.milliseconds).closing()

            shouldThrow<MailboxException> { runBlocking { mailbox.findLatest(ELI, SINCE) } }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `invalid arguments are rejected before any request`() {
        val server = serve(FakeMailpit())
        val mailbox = mailboxFor(server)

        runBlocking {
            shouldThrow<IllegalArgumentException> { mailbox.findLatest("", SINCE) }
            shouldThrow<IllegalArgumentException> { mailbox.findLatest("eli\"@x.az", SINCE) }
            shouldThrow<IllegalArgumentException> { mailbox.findLatest("eli @x.az", SINCE) }
            shouldThrow<IllegalArgumentException> { mailbox.findRecent(ELI, SINCE, limit = 0) }
            shouldThrow<IllegalArgumentException> { mailbox.markRead(" ") }
        }
        shouldThrow<IllegalArgumentException> { MailpitMailbox(URI("ftp://localhost:8025")) }
        shouldThrow<IllegalArgumentException> { MailpitMailbox(URI("/api")) }
        server.requests shouldBe emptyList()
    }

    @Test
    fun `credentials in the Mailpit URL are dropped and never shown`() {
        val mailpit = FakeMailpit()
        mailpit.add(stored("m1"))
        val server = serve(mailpit)
        val url = URI("http://mp-user:mp-pass-4f9a@${server.baseUrl.authority}")
        val mailbox = MailpitMailbox(url).closing()

        runBlocking { mailbox.findLatest(ELI, SINCE) }?.id shouldBe "m1"
        mailbox.toString() shouldNotContain "mp-pass-4f9a"
        server.requests.none { it.headers.containsKey("authorization") } shouldBe true

        val failing = MailpitMailbox(URI("http://mp-user:mp-pass-4f9a@${server.baseUrl.authority}/down")).closing()
        val error = shouldThrow<MailboxException> { runBlocking { failing.markRead("m1") } }
        error.message.shouldNotBeNull() shouldNotContain "mp-pass-4f9a"
        shouldThrow<IllegalArgumentException> { MailpitMailbox(URI("ftp://mp-user:mp-pass-4f9a@localhost")) }
            .message
            .shouldNotBeNull() shouldNotContain "mp-pass-4f9a"
    }

    @Test
    fun `closing the mailbox leaves an injected client open`() {
        val server = serve(FakeMailpit())
        val client = HttpClient(CIO).closing()

        MailpitMailbox(server.baseUrl, client).close()

        runBlocking { client.get("${server.baseUrl}/api/v1/search?query=x") }.status.value shouldBe 200
    }

    private fun readStatus(body: String): Pair<List<String>, Boolean> {
        val json = Json.parseToJsonElement(body).jsonObject
        return json.getValue("IDs").jsonArray.map { it.jsonPrimitive.content } to json.getValue("Read").jsonPrimitive.boolean
    }

    private companion object {
        const val ELI = "eli.k7x2.a07@test.kadrohr.com"
        val SINCE: Instant = Instant.parse("2026-09-25T10:00:00Z")

        fun stored(
            id: String,
            to: List<String> = listOf(ELI),
            created: String = "2026-09-25T14:00:01+04:00",
            text: String = "Kod: 482913",
            html: String = "",
            read: Boolean = false,
        ) = FakeMailpit.Stored(id, to, "Təsdiq kodu", created, text, html, read)
    }
}
