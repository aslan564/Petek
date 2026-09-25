package az.petek.faketarget

import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.Invite
import az.petek.faketarget.support.Page
import az.petek.faketarget.support.string
import az.petek.faketarget.support.toPage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

class MailpitApiTest {
    private val fake = FakeTargetFixture()

    @AfterEach
    fun tearDown() = fake.close()

    private suspend fun list(): Page = fake.http.get(fake.mailpit("/api/v1/messages")).toPage()

    private suspend fun search(query: String): Page = fake.http.get(fake.mailpit("/api/v1/search")) { parameter("query", query) }.toPage()

    private fun Page.messages(): List<JsonObject> = json()["messages"]!!.jsonArray.map { it.jsonObject }

    private fun Page.number(key: String): Int = json()[key]!!.jsonPrimitive.int

    /** Owner sign-up sends a verification mail; seeding sends two invitations. */
    private suspend fun threeMails() {
        val owner = fake.registerOwner(email = "boss@test.kadrohr.com")
        fake.seed(
            fake.companyOf(owner.email).string("id"),
            listOf("IT"),
            listOf(Invite("Ayan@Test.KadroHR.com", "Ayan", "employee", "IT"), Invite("vusal@test.kadrohr.com", "Vüsal", "employee", "IT")),
        )
    }

    @Test
    fun `messages are listed newest first in Mailpit's shape`() =
        runBlocking<Unit> {
            threeMails()
            val page = list()
            page.status shouldBe 200
            page.contentType!! shouldContain "application/json"
            page.number("total") shouldBe 3
            page.number("count") shouldBe 3
            page.number("messages_count") shouldBe 3
            page.number("unread") shouldBe 2
            val messages = page.messages()
            messages.map { it.string("Subject") } shouldContainExactly listOf("Dəvət", "Dəvət", "Təsdiq kodu")
            val created = messages.map { Instant.parse(it.string("Created")) }
            created shouldBe created.sortedDescending()

            val newest = messages.first()
            newest.keys shouldContainExactlyInAnyOrderOf
                listOf(
                    "ID",
                    "MessageID",
                    "Read",
                    "From",
                    "To",
                    "Cc",
                    "Bcc",
                    "ReplyTo",
                    "Subject",
                    "Created",
                    "Username",
                    "Tags",
                    "Size",
                    "Attachments",
                    "Snippet",
                )
            newest.string("ID") shouldBe
                fake.server.outbox
                    .messages()
                    .first()
                    .id
            newest.string("Read") shouldBe "false"
            val to = newest["To"]!!.jsonArray.single().jsonObject
            to.string("Name") shouldBe "Vüsal"
            to.string("Address") shouldBe "vusal@test.kadrohr.com"
            newest.string("Snippet") shouldContain "Qoşulmaq üçün keçid: http://"
        }

    @Test
    fun `to-search is case-insensitive and supports quotes, negation and free text`() =
        runBlocking<Unit> {
            threeMails()
            search("to:\"AYAN@test.kadrohr.com\"").messages().map { it.string("Subject") } shouldContainExactly listOf("Dəvət")
            search("to:ayan@test.kadrohr.com").messages() shouldHaveSize 1
            search("to:\"nobody@test.kadrohr.com\"").messages().shouldBeEmpty()

            val invitations = search("subject:Dəvət")
            invitations.number("messages_count") shouldBe 2
            invitations.number("total") shouldBe 3
            search("-subject:Dəvət").messages().single().string("Subject") shouldBe "Təsdiq kodu"
            search("təsdiq to:boss").messages() shouldHaveSize 1
            search("to:test.kadrohr.com").messages() shouldHaveSize 3
            search("").messages() shouldHaveSize 3
        }

    @Test
    fun `a message has ID, To, Subject, Date, Text and HTML and opening it marks it read`() =
        runBlocking<Unit> {
            threeMails()
            val summary = search("to:boss@test.kadrohr.com").messages().single()
            summary.string("Read") shouldBe "true"

            val invitation = search("to:vusal@test.kadrohr.com").messages().single()
            invitation.string("Read") shouldBe "false"
            val message = fake.http.get(fake.mailpit("/api/v1/message/${invitation.string("ID")}")).toPage()
            message.status shouldBe 200
            val body = message.json()
            listOf("ID", "To", "Subject", "Date", "Text", "HTML").forEach { body.containsKey(it) shouldBe true }
            body.string("ID") shouldBe invitation.string("ID")
            body.string("Subject") shouldBe "Dəvət"
            body.string("Text") shouldContain "/invite/"
            body.string("HTML") shouldContain "<a href="
            search("to:vusal@test.kadrohr.com").messages().single().string("Read") shouldBe "true"

            val latest =
                fake.http
                    .get(fake.mailpit("/api/v1/message/latest"))
                    .toPage()
                    .json()
            latest.string("ID") shouldBe list().messages().first().string("ID")
            fake.http
                .get(fake.mailpit("/api/v1/message/nope"))
                .toPage()
                .status shouldBe 404
        }

    @Test
    fun `read status can be set for chosen messages or all of them`() =
        runBlocking<Unit> {
            threeMails()
            val ids = list().messages().map { it.string("ID") }
            fake.http
                .put(fake.mailpit("/api/v1/read")) { setBody("""{"IDs":["${ids[0]}"],"Read":true}""") }
                .toPage()
                .body shouldBe "ok"
            list().messages().map { it.string("Read") } shouldContainExactly listOf("true", "false", "true")

            fake.http
                .put(fake.mailpit("/api/v1/messages")) { setBody("""{"IDs":[],"Read":false}""") }
                .toPage()
                .status shouldBe 200
            list().number("unread") shouldBe 3
            fake.http.put(fake.mailpit("/api/v1/read")) { setBody("""{"Search":"to:ayan@test.kadrohr.com"}""") }
            search("is:unread").messages() shouldHaveSize 2
            search("is:read")
                .messages()
                .single()["To"]!!
                .jsonArray
                .single()
                .jsonObject
                .string("Address") shouldBe "ayan@test.kadrohr.com"
            fake.http
                .put(fake.mailpit("/api/v1/read")) { setBody("{broken") }
                .toPage()
                .status shouldBe 400
        }

    @Test
    fun `messages can be deleted one by one or all at once`() =
        runBlocking<Unit> {
            threeMails()
            val ids = list().messages().map { it.string("ID") }
            fake.http
                .delete(fake.mailpit("/api/v1/messages")) { setBody("""{"IDs":["${ids[1]}"]}""") }
                .toPage()
                .status shouldBe 200
            list().messages().map { it.string("ID") } shouldContainExactly listOf(ids[0], ids[2])
            fake.http.delete(fake.mailpit("/api/v1/search")) { parameter("query", "subject:Dəvət") }
            list().messages().map { it.string("Subject") } shouldContainExactly listOf("Təsdiq kodu")
            fake.http.delete(fake.mailpit("/api/v1/messages"))
            list().number("total") shouldBe 0
            fake.server.outbox
                .messages()
                .shouldBeEmpty()
        }

    @Test
    fun `search results page with start and limit`() =
        runBlocking<Unit> {
            threeMails()
            val page =
                fake.http
                    .get(fake.mailpit("/api/v1/messages")) {
                        parameter("start", 1)
                        parameter("limit", 1)
                    }.toPage()
            page.number("start") shouldBe 1
            page.number("count") shouldBe 1
            page.number("messages_count") shouldBe 3
            page.messages().single().string("Subject") shouldBe "Dəvət"
        }

    @Test
    fun `the mail port also serves health checks and a small inbox page`() =
        runBlocking<Unit> {
            threeMails()
            fake.http
                .get(fake.mailpit("/livez"))
                .toPage()
                .body shouldBe "ok"
            fake.http
                .get(fake.mailpit("/api/v1/info"))
                .toPage()
                .json()["Messages"]!!
                .jsonPrimitive.int shouldBe 3
            val inbox = fake.http.get(fake.mailpit("/")).toPage()
            inbox.status shouldBe 200
            inbox.body shouldContain "vusal@test.kadrohr.com"
        }

    private infix fun Set<String>.shouldContainExactlyInAnyOrderOf(expected: List<String>) {
        this shouldBe expected.toSet()
    }
}
