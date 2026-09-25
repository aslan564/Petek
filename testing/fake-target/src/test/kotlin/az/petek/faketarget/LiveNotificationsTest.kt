package az.petek.faketarget

import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.LiveStream
import az.petek.faketarget.support.string
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class LiveNotificationsTest {
    private val fake = FakeTargetFixture()

    @AfterEach
    fun tearDown() = fake.close()

    @Test
    fun `an open event stream receives the announcement live`() =
        runBlocking<Unit> {
            val team = fake.team()
            LiveStream(team.itEmployee.browser).use { stream ->
                stream.awaitConnected()
                val created = team.admin.browser.submit("/announcements", "title" to "Sabah 10:00 ümumi iclas", "body" to "")
                val id = created.location!!.substringAfterLast('/')

                val event = stream.awaitEvent()
                event.event shouldBe "notification"
                event.id!! shouldStartWith "n"
                val notification = Json.parseToJsonElement(event.data!!).jsonObject
                notification.string("id") shouldBe event.id
                notification.string("type") shouldBe "announcement"
                notification.string("object_id") shouldBe id
                notification.string("text") shouldBe "Sabah 10:00 ümumi iclas"
                notification.string("link") shouldBe "/announcements/$id"
            }
        }

    @Test
    fun `each stream carries only its own user's notifications`() =
        runBlocking<Unit> {
            val team = fake.team()
            LiveStream(team.admin.browser).use { adminStream ->
                LiveStream(team.hrEmployee.browser).use { employeeStream ->
                    adminStream.awaitConnected()
                    employeeStream.awaitConnected()
                    team.admin.browser.submit("/announcements", "title" to "Birinci", "body" to "")
                    team.admin.browser.submit("/announcements", "title" to "İkinci", "body" to "")
                    employeeStream.awaitNotification().string("text") shouldBe "Birinci"
                    employeeStream.awaitNotification().string("text") shouldBe "İkinci"
                    adminStream.drain().shouldBeEmpty()
                }
            }
        }

    @Test
    fun `a stream replays what the page missed after the given id and after Last-Event-ID`() =
        runBlocking<Unit> {
            val team = fake.team()
            listOf("A", "B", "C").forEach { team.admin.browser.submit("/announcements", "title" to it, "body" to "") }
            val ids =
                fake.server.store
                    .notifications(team.itEmployee.email)
                    .map { it.id }

            LiveStream(team.itEmployee.browser, query = "?after=${ids[0]}").use { stream ->
                listOf(stream.awaitNotification(), stream.awaitNotification()).map { it.string("text") } shouldContainExactly
                    listOf("B", "C")
            }
            LiveStream(team.itEmployee.browser, query = "?after=${ids[0]}", lastEventId = ids[1]).use { stream ->
                stream.awaitNotification().string("text") shouldBe "C"
            }
            LiveStream(team.itEmployee.browser, query = "?after=${ids[2]}").use { stream ->
                stream.awaitConnected()
                stream.drain().shouldBeEmpty()
            }
        }

    @Test
    fun `the event stream needs a session`() =
        runBlocking<Unit> {
            val response = fake.http.get(fake.web("/events"))
            response.status.value shouldBe 401
            response.bodyAsText() shouldBe """{"error":"not_logged_in","message":"Davam etmək üçün daxil olun."}"""
        }

    @Test
    fun `the event stream is served as text-event-stream without caching`() =
        runBlocking<Unit> {
            val team = fake.team()
            val headers = LiveStream(team.itEmployee.browser).use { it.headers() }
            headers[HttpHeaders.ContentType]!! shouldStartWith "text/event-stream"
            headers[HttpHeaders.CacheControl] shouldBe "no-store"
        }

    @Test
    fun `every logged-in page carries the live script and where to resume`() =
        runBlocking<Unit> {
            val team = fake.team()
            team.admin.browser.submit("/announcements", "title" to "Elan", "body" to "")
            val lastId =
                fake.server.store
                    .notifications(team.itEmployee.email)
                    .last()
                    .id
            listOf("/", "/announcements", "/tickets", "/notifications").forEach { path ->
                val page = team.itEmployee.browser.get(path)
                page.attribute("notification-list", "data-last-id") shouldBe lastId
                page.body.contains("addEventListener('notification'") shouldBe true
                page.has("current-user-name") shouldBe true
            }
        }
}
