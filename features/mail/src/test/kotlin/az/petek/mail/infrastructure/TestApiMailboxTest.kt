/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.infrastructure

import az.petek.core.security.Secret
import az.petek.mail.application.DefaultAwaitVerificationUseCase
import az.petek.mail.domain.DefaultVerificationExtractor
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailboxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** [TestApiMailbox] against an embedded server speaking the target's `/test/emails` API. */
class TestApiMailboxTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    /** The target's stored outgoing mail, answered newest first like the real test API. */
    private class FakeTestApi(
        val token: String = TOKEN,
        val readEndpoint: Boolean = true,
    ) {
        val mails = CopyOnWriteArrayList<JsonObject>()
        val readIds = CopyOnWriteArrayList<String>()

        fun add(
            id: String,
            to: String = ELI,
            createdAt: String = "2026-09-25T10:00:01Z",
            text: String = "Təsdiq kodu: 482913",
            html: String? = null,
            links: List<String> = emptyList(),
            read: Boolean = false,
            subject: String = "Kadro HR - Email Təsdiqləmə",
        ) {
            mails +=
                buildJsonObject {
                    put("id", id)
                    put("to", to)
                    put("subject", subject)
                    put("text", text)
                    html?.let { put("html", it) }
                    put("links", JsonArray(links.map(::JsonPrimitive)))
                    put("created_at", createdAt)
                    put("read", read)
                }
        }

        fun handle(request: RecordedRequest): StubResponse {
            if (request.headers["x-test-token"] != token) return StubResponse(401, """{"error":"unauthorized"}""")
            return when {
                request.method == "GET" && request.path == "/gw/test/emails" -> {
                    val to = request.query["to"]?.single()
                    val listed = mails.filter { it["to"].toString().contains(to.orEmpty(), ignoreCase = true) }.reversed()
                    StubResponse(body = JsonArray(listed).toString())
                }

                request.method == "POST" && request.path.startsWith("/gw/test/emails/") && request.path.endsWith("/read") -> {
                    if (!readEndpoint) return StubResponse(404, "")
                    val id = request.path.removePrefix("/gw/test/emails/").removeSuffix("/read")
                    readIds += id
                    val index = mails.indexOfFirst { (it["id"] as JsonPrimitive).content == id }
                    if (index >= 0) mails[index] = JsonObject(mails[index] + ("read" to JsonPrimitive(true)))
                    StubResponse(204)
                }

                else -> {
                    StubResponse(404, "")
                }
            }
        }
    }

    private fun serve(api: FakeTestApi) = StubHttpServer { api.handle(it) }.closing()

    private fun mailboxFor(
        server: StubHttpServer,
        token: String? = TOKEN,
    ) = TestApiMailbox(URI("${server.baseUrl}/gw/"), token?.let(::Secret)).closing()

    @Test
    fun `finds the newest message to the tester and sends the token`() {
        val api = FakeTestApi()
        api.add("e1", createdAt = "2026-09-25T10:00:01Z", text = "Kod: 111111")
        api.add("e2", createdAt = "2026-09-25T14:00:02+04:00", text = "Kod: 222222", html = "<p>Kod: 222222</p>")
        val server = serve(api)

        val message = runBlocking { mailboxFor(server).findLatest(ELI, SINCE) }.shouldNotBeNull()

        message.id shouldBe "e2"
        message.to shouldContainExactly listOf(ELI)
        message.subject shouldBe "Kadro HR - Email Təsdiqləmə"
        message.receivedAt shouldBe Instant.parse("2026-09-25T10:00:02Z")
        message.text shouldBe "Kod: 222222"
        message.html shouldBe "<p>Kod: 222222</p>"
        message.read shouldBe false
        val search = server.requests.single()
        search.path shouldBe "/gw/test/emails"
        search.query["to"] shouldBe listOf(ELI)
        search.headers["x-test-token"] shouldBe TOKEN
    }

    @Test
    fun `other recipients, older and read messages are left out`() {
        val api = FakeTestApi()
        api.add("other", to = "veli.$ELI")
        api.add("before", createdAt = "2026-09-25T09:59:59Z")
        api.add("read", read = true)
        api.add("mine", createdAt = "2026-09-25T10:00:00Z")
        val mailbox = mailboxFor(serve(api))

        runBlocking { mailbox.findRecent(ELI, SINCE) }.map { it.id } shouldContainExactly listOf("mine")
        runBlocking { mailbox.findRecent(ELI, SINCE, unreadOnly = false) }.map { it.id } shouldContainExactly listOf("read", "mine")
    }

    @Test
    fun `recipients may be listed and compared without case`() {
        val api = FakeTestApi()
        api.mails +=
            buildJsonObject {
                put("id", "e1")
                put("to", buildJsonArray { add(JsonPrimitive("hr@test.kadrohr.com")) })
                put("created_at", "2026-09-25T10:00:01Z")
            }
        api.mails +=
            buildJsonObject {
                put("id", "e2")
                put("to", buildJsonArray { add(JsonPrimitive("hr@test.kadrohr.com")) })
                put("created_at", "2026-09-25T10:00:02Z")
            }
        api.add("e3", to = ELI.uppercase(), createdAt = "2026-09-25T10:00:03Z")
        val mailbox = mailboxFor(serve(api))

        runBlocking { mailbox.findLatest(ELI, SINCE) }?.id shouldBe "e3"
        runBlocking { mailbox.findRecent("hr@test.kadrohr.com", SINCE) }.map { it.id } shouldContainExactly listOf("e2", "e1")
    }

    @Test
    fun `links the API lists separately are added to the text`() {
        val api = FakeTestApi()
        val invite = "https://api.kadrohr.test/api/v1/auth/set-password?token=abc"
        api.add(
            "e1",
            text = "Sizi dəvət etdilər",
            links = listOf(invite, "https://kadrohr.test/"),
            html = "<a href=\"https://kadrohr.test/\">x</a>",
        )

        val message = runBlocking { mailboxFor(serve(api)).findLatest(ELI, SINCE) }.shouldNotBeNull()

        message.text shouldBe "Sizi dəvət etdilər\n$invite"
    }

    @Test
    fun `markRead posts to the read endpoint and the message is read afterwards`() {
        val api = FakeTestApi()
        api.add("e1")
        val server = serve(api)
        val mailbox = mailboxFor(server)

        runBlocking { mailbox.markRead("e1") }

        api.readIds shouldContainExactly listOf("e1")
        server.requests.last().method shouldBe "POST"
        server.requests.last().path shouldBe "/gw/test/emails/e1/read"
        runBlocking { mailbox.findLatest(ELI, SINCE) }.shouldBeNull()
    }

    @Test
    fun `without a read endpoint used messages are remembered locally and the endpoint is not asked again`() {
        val api = FakeTestApi(readEndpoint = false)
        api.add("e1", createdAt = "2026-09-25T10:00:01Z")
        api.add("e2", createdAt = "2026-09-25T10:00:02Z")
        val server = serve(api)
        val mailbox = mailboxFor(server)

        runBlocking {
            mailbox.markRead("e2")
            mailbox.markRead("e1")
        }

        server.requests.count { it.method == "POST" } shouldBe 1
        runBlocking { mailbox.findLatest(ELI, SINCE) }.shouldBeNull()
        runBlocking { mailbox.findLatest(ELI, SINCE, unreadOnly = false) }?.read shouldBe true
    }

    @Test
    fun `a refused token is a mailbox error that hints at the token without showing it`() {
        val server = serve(FakeTestApi(token = "the-right-one"))

        val error = shouldThrow<MailboxException> { runBlocking { mailboxFor(server, token = TOKEN).findLatest(ELI, SINCE) } }

        error.message!! shouldContain "answered HTTP 401 (check PETEK_TEST_TOKEN"
        error.message!! shouldNotContain TOKEN
    }

    @Test
    fun `without a token no token header is sent`() {
        val server = serve(FakeTestApi())

        shouldThrow<MailboxException> { runBlocking { mailboxFor(server, token = null).findLatest(ELI, SINCE) } }

        server.requests
            .single()
            .headers
            .containsKey("x-test-token") shouldBe false
    }

    @Test
    fun `unexpected answers are mailbox errors`() {
        val statuses = StubHttpServer { StubResponse(500, "boom") }.closing()
        shouldThrow<MailboxException> { runBlocking { mailboxFor(statuses).findLatest(ELI, SINCE) } }.message!! shouldContain
            "answered HTTP 500: boom"

        val garbage = StubHttpServer { StubResponse(body = "<html>login</html>") }.closing()
        shouldThrow<MailboxException> { runBlocking { mailboxFor(garbage).findLatest(ELI, SINCE) } }.message!! shouldContain
            "returned unexpected JSON"

        val listing = """{"emails": [{"id": "e1", "to": "$ELI", "created_at": "2026-09-25T10:00:05Z"}]}"""
        val wrapped = StubHttpServer { StubResponse(body = listing) }.closing()
        runBlocking { mailboxFor(wrapped).findLatest(ELI, SINCE) }?.id shouldBe "e1"
    }

    @Test
    fun `an unreachable test API is a mailbox error`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val mailbox = TestApiMailbox(URI("http://127.0.0.1:$port"), Secret(TOKEN)).closing()

        shouldThrow<MailboxException> { runBlocking { mailbox.findLatest(ELI, SINCE) } }.message!! shouldContain
            "search for mail to $ELI failed"
    }

    @Test
    fun `messages without an id or a readable time are skipped`() {
        val api = FakeTestApi()
        api.mails += buildJsonObject { put("to", ELI) }
        api.add("bad-time", createdAt = "yesterday")
        api.add("good")
        val mailbox = mailboxFor(serve(api))

        runBlocking { mailbox.findRecent(ELI, SINCE) }.map { it.id } shouldContainExactly listOf("good")
    }

    @Test
    fun `the verification use case reads codes and pattern links through the test API`() {
        val api = FakeTestApi()
        val invite = "https://api.kadrohr.test/api/v1/auth/set-password?token=9f1c"
        api.add("code", createdAt = "2026-09-25T10:00:01Z", text = "Təsdiq kodu: 482913")
        api.add("invite", createdAt = "2026-09-25T10:00:02Z", text = "Sizi dəvət etdilər: $invite")
        val mailbox = mailboxFor(serve(api))
        val useCase = DefaultAwaitVerificationUseCase(mailbox, DefaultVerificationExtractor())

        val link = runBlocking { useCase.awaitLink(ELI, SINCE, Regex("set-password\\?token="), 1.seconds, 1.seconds) }
        val code = runBlocking { useCase.await(ELI, SINCE, MailPurpose.CODE, 1.seconds, 1.seconds) }

        link.link.toString() shouldBe invite
        code.code shouldBe "482913"
        api.readIds shouldContainExactly listOf("invite", "code")
    }

    @Test
    fun `a malformed base address or token is refused at construction`() {
        shouldThrow<IllegalArgumentException> { TestApiMailbox(URI("ftp://x"), Secret(TOKEN)) }
        shouldThrow<IllegalArgumentException> { TestApiMailbox(URI("https://x"), Secret("a\nb")) }
    }

    private companion object {
        const val ELI = "eli.k7x2.a07@test.kadrohr.com"
        const val TOKEN = "secret-test-token-1"
        val SINCE: Instant = Instant.parse("2026-09-25T10:00:00Z")
    }
}
