package az.petek.faketarget

import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.toPage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

class FakeTargetServerTest {
    @Test
    fun `urls are only known while the server runs`() {
        val server = FakeTargetServer()
        shouldThrow<IllegalStateException> { server.baseUrl }
        server.start().use {
            it.baseUrl.host shouldBe "127.0.0.1"
            it.mailpitUrl.host shouldBe "127.0.0.1"
            it.baseUrl.port shouldNotBe it.mailpitUrl.port
        }
        shouldThrow<IllegalStateException> { server.mailpitUrl }
    }

    @Test
    fun `a server starts once and stops idempotently`() {
        val server = FakeTargetServer().start()
        shouldThrow<IllegalStateException> { server.start() }
        server.stop()
        server.stop()
        server.close()
        shouldThrow<IllegalStateException> { server.start() }
    }

    @Test
    fun `both ports answer and unknown pages are 404 in the right format`() =
        runBlocking<Unit> {
            FakeTargetServer().start().use { server ->
                HttpClient(CIO) { expectSuccess = false }.use { http ->
                    http.get(server.baseUrl.resolve("/healthz").toString()).toPage().body shouldBe "ok"
                    http.get(server.mailpitUrl.resolve("/readyz").toString()).toPage().body shouldBe "ok"

                    val html = http.get(server.baseUrl.resolve("/no/such/page").toString()).toPage()
                    html.status shouldBe 404
                    html.text("page-error") shouldBe "Səhifə tapılmadı."
                    val json = http.get(server.baseUrl.resolve("/api/nothing").toString()).toPage()
                    json.status shouldBe 404
                    json.contentType!! shouldContain "application/json"
                }
            }
        }

    @Test
    fun `the store offers read-only snapshots as properties and as functions`() =
        runBlocking<Unit> {
            FakeTargetFixture().use { fake ->
                val team = fake.team()
                team.admin.browser.submit("/announcements", "title" to "Elan", "body" to "")
                team.itEmployee.browser.submit("/tickets", "title" to "T", "description" to "", "department" to "IT")
                val store = fake.server.store
                store.users shouldBe store.users()
                store.users.map { it.email } shouldContainExactlyInAnyOrder team.everyone.map { it.email }
                store.companies shouldBe store.companies()
                store.invitations shouldBe store.invitations()
                store.announcements.single().title shouldBe "Elan"
                store.tickets.single().title shouldBe "T"
                store.notifications shouldBe store.notifications()
                // Five announcement recipients plus the IT manager, who is told about the new IT ticket.
                store.notifications.size shouldBe 6
                store.receipts(store.announcements.single().id) shouldBe emptyList()
            }
        }

    @Test
    fun `an occupied port makes the start fail loudly`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            shouldThrow<Exception> { FakeTargetServer().start(mailPort = taken.localPort) }
            shouldThrow<Exception> { FakeTargetServer().start(port = taken.localPort) }
        }
    }
}
