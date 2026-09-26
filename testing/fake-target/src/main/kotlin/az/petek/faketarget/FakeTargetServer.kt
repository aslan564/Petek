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

import az.petek.faketarget.api.TestApi
import az.petek.faketarget.api.TicketApi
import az.petek.faketarget.mail.MailOutbox
import az.petek.faketarget.mail.MailpitApi
import az.petek.faketarget.service.AccountService
import az.petek.faketarget.service.AnnouncementService
import az.petek.faketarget.service.CompanyService
import az.petek.faketarget.service.Mailer
import az.petek.faketarget.service.NotificationHub
import az.petek.faketarget.service.NotificationService
import az.petek.faketarget.service.SecretGenerator
import az.petek.faketarget.service.TestQueries
import az.petek.faketarget.service.TicketService
import az.petek.faketarget.store.FakeTargetStore
import az.petek.faketarget.web.AppRoutes
import az.petek.faketarget.web.AuthRoutes
import az.petek.faketarget.web.EventsRoute
import az.petek.faketarget.web.WebApplication
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * A small portal-like web application implementing docs/TARGET_CONTRACT.md: server-rendered pages with the contract's
 * `data-testid`s, sessions, roles, live notifications over SSE, the `/test/...` API and, on a second port, a
 * Mailpit-compatible API serving the e-mails it "sends". State lives in memory ([store]) and dies with the server.
 *
 * Both ports bind to the loopback interface only. Start once, stop (or [close]) once; a stopped server cannot be restarted.
 */
class FakeTargetServer(
    val config: FakeTargetConfig = FakeTargetConfig(),
) : AutoCloseable {
    private val clock: Clock = Clock.systemUTC()
    private val secrets = SecretGenerator()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("fake-target") +
                CoroutineExceptionHandler { _, e -> logger.error(e) { "Background task of the fake target failed" } },
        )
    private val hub = NotificationHub()

    /** Read access to everything the fake knows, for test assertions. */
    val store: FakeTargetStore = FakeTargetStore()

    /** Every e-mail the fake sent, newest first via [MailOutbox.messages] (also served by the Mailpit API). */
    val outbox: MailOutbox = MailOutbox(clock, secrets::mailId)

    private val lock = Any()
    private var running: Running? = null
    private var stopped = false

    private class Running(
        val web: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
        val mail: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
        val baseUrl: URI,
        val mailpitUrl: URI,
    )

    /** Where the web application listens, e.g. `http://127.0.0.1:8080`. Only valid after [start]. */
    val baseUrl: URI get() = requireRunning().baseUrl

    /** Where the Mailpit-compatible API listens, e.g. `http://127.0.0.1:8025`. Only valid after [start]. */
    val mailpitUrl: URI get() = requireRunning().mailpitUrl

    /**
     * Starts both servers; port 0 picks a free port. Blocks until both accept connections. A taken port fails with an
     * [IllegalStateException] naming it, and nothing is left running.
     */
    fun start(
        port: Int = 0,
        mailPort: Int = 0,
    ): FakeTargetServer {
        synchronized(lock) {
            check(running == null && !stopped) { "FakeTargetServer can be started only once" }
            val web = startEngine("web application", port) { webApplication().install(this) }
            val mail =
                try {
                    startEngine("Mailpit API", mailPort) { MailpitApi(outbox, json).install(this) }
                } catch (e: IllegalStateException) {
                    web.stop(0, 0)
                    throw e
                }
            val webPort =
                runBlocking {
                    web.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val mailpitPort =
                runBlocking {
                    mail.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            running = Running(web, mail, URI("http://$HOST:$webPort"), URI("http://$HOST:$mailpitPort"))
            logger.info { "Fake target on http://$HOST:$webPort, Mailpit API on http://$HOST:$mailpitPort ($config)" }
        }
        return this
    }

    /** Ends live streams and stops both servers. Idempotent. */
    fun stop() {
        val current =
            synchronized(lock) {
                stopped = true
                running.also { running = null }
            } ?: return
        hub.closeAll()
        scope.cancel()
        current.web.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        current.mail.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
    }

    override fun close() = stop()

    /** CIO reports a taken port as a bare coroutine cancellation; name the port so the failure is actionable. */
    private fun startEngine(
        what: String,
        port: Int,
        module: Application.() -> Unit,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        try {
            embeddedServer(CIO, port = port, host = HOST, module = module).start(wait = false)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot start the fake target's $what on $HOST:$port (is the port already in use?)", e)
        }

    private fun requireRunning(): Running = synchronized(lock) { checkNotNull(running) { "FakeTargetServer is not running" } }

    private fun webApplication(): WebApplication {
        val mailer = Mailer(outbox)
        val notifications = NotificationService(store, hub, clock)
        val accounts = AccountService(store, config, mailer, secrets, clock)
        val companies = CompanyService(store, mailer, secrets, hub, clock)
        val announcements = AnnouncementService(store, config, notifications, scope, clock)
        val tickets = TicketService(store, config, notifications, clock)
        return WebApplication(
            auth = AuthRoutes(accounts),
            app = AppRoutes(accounts, companies, announcements, tickets, notifications),
            events = EventsRoute(accounts, notifications, json),
            ticketApi = TicketApi(accounts, tickets, json),
            testApi = TestApi(config.testToken, TestQueries(store), companies, json),
            json = json,
        )
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val GRACE_MILLIS = 100L
        const val TIMEOUT_MILLIS = 2_000L
    }
}
