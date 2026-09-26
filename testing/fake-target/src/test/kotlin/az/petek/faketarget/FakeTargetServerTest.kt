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

package az.petek.faketarget

import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.LiveStream
import az.petek.faketarget.support.toPage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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
    fun `a logged-in visitor keeps the session header and notification panel on error pages`() =
        runBlocking<Unit> {
            FakeTargetFixture().use { fake ->
                val owner = fake.registerOwner()
                val missing = owner.browser.get("/no/such/page")
                missing.status shouldBe 404
                missing.text("page-error") shouldBe "Səhifə tapılmadı."
                missing.text("current-user-name") shouldBe owner.name
                listOf("logout", "nav-home", "notification-bell", "notification-count", "notification-list").forEach {
                    missing.has(it) shouldBe true
                }
                fake
                    .browser()
                    .get("/no/such/page")
                    .has("current-user-name") shouldBe false
            }
        }

    @Test
    fun `no response of the web port may be cached, so Back never shows a stale page`() =
        runBlocking<Unit> {
            FakeTargetFixture().use { fake ->
                val owner = fake.registerOwner()
                val responses =
                    listOf(
                        owner.browser.client.get(owner.browser.url("/")),
                        owner.browser.client.get(owner.browser.url("/login")),
                        owner.browser.client.get(owner.browser.url("/no/such/page")),
                        owner.browser.client.get(owner.browser.url("/api/me")),
                        fake.http.get(fake.web("/test/companies?owner=${owner.email}")) { header("X-Test-Token", fake.config.testToken) },
                    )
                responses.forEach { it.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store") }
                LiveStream(owner.browser).use { it.headers().getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store") }
                owner.browser.get("/").body shouldContain "event.persisted"
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
    fun `an occupied port makes the start fail loudly and leaves nothing running`() {
        val free = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val mailTaken = shouldThrow<IllegalStateException> { FakeTargetServer().start(port = free, mailPort = taken.localPort) }
            mailTaken.message shouldContain "Mailpit API on 127.0.0.1:${taken.localPort}"
            val webTaken = shouldThrow<IllegalStateException> { FakeTargetServer().start(port = taken.localPort) }
            webTaken.message shouldContain "web application on 127.0.0.1:${taken.localPort}"
        }
        // The web application that did start is stopped again, so its port is free.
        ServerSocket(free, 50, InetAddress.getLoopbackAddress()).close()
    }
}
