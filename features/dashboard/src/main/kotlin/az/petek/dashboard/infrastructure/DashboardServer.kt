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

package az.petek.dashboard.infrastructure

import az.petek.core.ids.AgentId
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.application.UnavailablePanelBackend
import az.petek.dashboard.domain.PanelBackend
import az.petek.evidence.domain.ArtifactStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/**
 * The Pətək web panel (Ktor CIO), bound to the loopback interface only: the live agent board plus the screens backed
 * by [backend] (instructions, explorer, scenarios, orchestrator, reports).
 *
 * Endpoints:
 * - `GET /` — the single-page panel (Azerbaijani, light/dark, responsive).
 * - Live board: `GET /api/snapshot`, `GET /api/agents/{agentId}` (one agent with its own recent timeline),
 *   `GET /api/orchestrator` (plan, task matrix, events) and `GET /api/stream?topics=...` (Server-Sent Events, see
 *   [streamRoutes]).
 * - Panel: the REST API of [panelRoutes] (capacity, exploration, scenarios, runs, triage, stability).
 * - Files: artifacts and reports, see [fileRoutes].
 *
 * Security: requests whose `Host` or `Origin` is not local are refused (DNS rebinding); every request other than GET
 * or HEAD must carry the per-process token of the page in `X-Petek-Token` (no other site can act through the owner's
 * browser); there is no CORS. Every response forbids MIME sniffing and framing; the page, artifacts and reports each
 * get a Content-Security-Policy that allows no foreign origin.
 *
 * Start once, stop (or [close]) once; a stopped server cannot be restarted.
 *
 * @param reportDirectory where the shown run's report is, or null while there is none; by default the directory the
 *   finished run reported ([LiveDashboard.reportPath]).
 * @param host a loopback address or name (`127.0.0.1`, `::1`, `localhost`); anything else is rejected.
 * @param port the port to listen on; 0 picks a free one (see the URI [start] returns).
 * @param backend everything besides the live board; by default none (those screens then show that they are empty).
 * @param refreshInterval the fastest pace of the exploration stream (the board's is [LiveDashboard]'s own).
 * @param defaultTarget the site the instruction screen's "Hədəf sayt" starts with (e.g. the configured `PETEK_TARGET`);
 *   the owner may change it.
 */
class DashboardServer(
    private val dashboard: LiveDashboard,
    private val artifacts: ArtifactStore,
    private val reportDirectory: () -> Path? = { dashboard.reportPath?.let(Path::of) },
    private val host: String = "127.0.0.1",
    private val port: Int = 7070,
    private val backend: PanelBackend = UnavailablePanelBackend(),
    private val refreshInterval: Duration = 250.milliseconds,
    defaultTarget: String? = null,
) : AutoCloseable {
    init {
        require(port in 0..MAX_PORT) { "port must be in 0..$MAX_PORT, was $port" }
        require(RequestGuard.isLoopback(host)) { "The dashboard binds to the loopback interface only; '$host' is not a loopback address" }
    }

    private val guard = RequestGuard(host)
    private val page = DashboardPage(guard.token, defaultTarget)
    private val lock = Any()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var address: URI? = null
    private var stopped = false

    /** Where the page is served, e.g. `http://127.0.0.1:7070/`. Only valid after [start]. */
    val uri: URI get() = synchronized(lock) { checkNotNull(address) { "DashboardServer is not running" } }

    /**
     * Starts listening and returns the page's URI. Blocks until the port is bound. A taken port fails with an
     * [IllegalStateException] naming it.
     */
    fun start(): URI =
        synchronized(lock) {
            check(server == null && !stopped) { "DashboardServer can be started only once" }
            val started =
                try {
                    embeddedServer(CIO, port = port, host = host) { module() }.start(wait = false)
                } catch (e: Exception) {
                    throw IllegalStateException("Cannot start the dashboard on $host:$port (is the port already in use?)", e)
                }
            val boundPort =
                runBlocking {
                    started.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val uri = URI("http://${urlHost()}:$boundPort/")
            server = started
            address = uri
            logger.info { "Pətək panel on $uri" }
            uri
        }

    /** Ends open streams and stops listening. Idempotent. */
    fun stop() {
        val current =
            synchronized(lock) {
                stopped = true
                address = null
                server.also { server = null }
            } ?: return
        current.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
    }

    override fun close() = stop()

    private fun urlHost(): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    // --- application ----------------------------------------------------------------------------------------------

    private fun Application.module() {
        install(SSE)
        intercept(ApplicationCallPipeline.Plugins) {
            val call = context
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Referrer-Policy", "no-referrer")
            val refusal =
                when {
                    !guard.hostAllowed(call.request.headers[HttpHeaders.Host]) -> {
                        "Yalnız lokal ünvandan açıla bilər."
                    }

                    !guard.originAllowed(call.request.headers[HttpHeaders.Origin]) -> {
                        "Başqa saytdan gələn sorğu qəbul edilmir."
                    }

                    call.request.httpMethod !in SAFE_METHODS && !guard.tokenValid(call.request.headers[RequestGuard.TOKEN_HEADER]) -> {
                        "Sorğu panelin öz açarı olmadan göndərilib."
                    }

                    else -> {
                        null
                    }
                }
            if (refusal != null) {
                call.respondText(PanelJson.error(refusal), ContentType.Application.Json, HttpStatusCode.Forbidden)
                finish()
            }
        }
        routing {
            pageRoutes()
            boardRoutes()
            panelRoutes(backend)
            streamRoutes(dashboard, backend, ::reportReady, refreshInterval)
            fileRoutes(dashboard, artifacts, backend, reportDirectory)
        }
    }

    private fun Route.pageRoutes() {
        get("/") {
            val rendered = page.render()
            call.response.header(HttpHeaders.CacheControl, NO_STORE)
            call.response.header("Content-Security-Policy", rendered.contentSecurityPolicy)
            call.respondText(rendered.html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
        }
    }

    private fun Route.boardRoutes() {
        get("/api/snapshot") {
            call.response.header(HttpHeaders.CacheControl, NO_STORE)
            call.respondText(DashboardJson.snapshot(dashboard.snapshot(), reportReady()), ContentType.Application.Json)
        }
        get("/api/agents/{agentId}") {
            val detail = agentIdOrNull(call.parameters["agentId"])?.let(dashboard::agentDetail)
            if (detail == null) return@get call.notFound()
            call.response.header(HttpHeaders.CacheControl, NO_STORE)
            call.respondText(DashboardJson.detail(detail), ContentType.Application.Json)
        }
        get("/api/orchestrator") {
            call.response.header(HttpHeaders.CacheControl, NO_STORE)
            call.respondText(PanelJson.orchestrator(dashboard.orchestrator()), ContentType.Application.Json)
        }
    }

    private suspend fun reportReady(): Boolean = withContext(Dispatchers.IO) { reportDirectory()?.isDirectory() == true }

    private companion object {
        const val MAX_PORT = 65_535
        const val GRACE_MILLIS = 100L
        const val TIMEOUT_MILLIS = 2_000L
        const val NO_STORE = "no-store"
        val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)

        fun agentIdOrNull(text: String?): AgentId? =
            try {
                text?.let(::AgentId)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
