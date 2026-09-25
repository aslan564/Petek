package az.petek.dashboard.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.dashboard.application.LiveDashboard
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The live dashboard's web server (Ktor CIO), bound to the loopback interface only.
 *
 * Endpoints:
 * - `GET /` — the page (Azerbaijani, light/dark, responsive); it renders `/api/snapshot`, then follows `/api/stream`.
 * - `GET /api/snapshot` — the current snapshot as JSON; `GET /api/agents/{agentId}` — one agent with its own recent
 *   timeline; `GET /api/stream` — Server-Sent Events, one `snapshot` event at once and then at most four per second
 *   while something changes, plus a heartbeat comment every 15 s (EventSource reconnects by itself).
 * - `GET /artifacts/{artifactId}` — a recorded artifact of the run on the board (screenshots as PNG, captured text as
 *   `text/plain`), found through the records the dashboard saw; the client never names a path. Immutable, so cached.
 * - `GET /report/...` — the run's report directory once it exists ([reportDirectory]), with path-traversal protection;
 *   `GET /{owner}/{file}` answers the report's relative evidence links (`../a07/0003-screenshot.png`) with the recorded
 *   artifact of that path, and nothing else.
 *
 * Every response forbids MIME sniffing and framing; the page, the artifacts and the report each get a
 * Content-Security-Policy that allows no foreign origin. Requests whose `Host` is not a loopback name are refused, so
 * a web page elsewhere cannot read the dashboard through DNS rebinding.
 *
 * Start once, stop (or [close]) once; a stopped server cannot be restarted.
 *
 * @param reportDirectory where the shown run's report is, or null while there is none; by default the directory the
 *   finished run reported ([LiveDashboard.reportPath]).
 * @param host a loopback address or name (`127.0.0.1`, `::1`, `localhost`); anything else is rejected.
 * @param port the port to listen on; 0 picks a free one (see the URI [start] returns).
 */
class DashboardServer(
    private val dashboard: LiveDashboard,
    private val artifacts: ArtifactStore,
    private val reportDirectory: () -> Path? = { dashboard.reportPath?.let(Path::of) },
    private val host: String = "127.0.0.1",
    private val port: Int = 7070,
) : AutoCloseable {
    init {
        require(port in 0..MAX_PORT) { "port must be in 0..$MAX_PORT, was $port" }
        require(isLoopback(host)) { "The dashboard binds to the loopback interface only; '$host' is not a loopback address" }
    }

    private val page = DashboardPage()
    private val allowedHosts = setOf("127.0.0.1", "localhost", "::1", host.lowercase().removeSurrounding("[", "]"))
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
                    embeddedServer(CIO, port = port, host = host) { install() }.start(wait = false)
                } catch (e: Exception) {
                    throw IllegalStateException("Cannot start the dashboard on $host:$port (is the port already in use?)", e)
                }
            val boundPort = runBlocking { started.engine.resolvedConnectors().first().port }
            val uri = URI("http://${urlHost()}:$boundPort/")
            server = started
            address = uri
            logger.info { "Dashboard on $uri" }
            uri
        }

    /** Ends open streams and stops listening. Idempotent. */
    fun stop() {
        val current =
            synchronized(lock) {
                stopped = true
                server.also { server = null }
            } ?: return
        current.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
    }

    override fun close() = stop()

    private fun urlHost(): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    // --- application ----------------------------------------------------------------------------------------------

    private fun Application.install() {
        install(SSE)
        intercept(ApplicationCallPipeline.Plugins) {
            val call = context
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Referrer-Policy", "no-referrer")
            if (!hostAllowed(call.request.headers[HttpHeaders.Host])) {
                call.respondText("Yalnız lokal ünvandan açıla bilər.", status = HttpStatusCode.Forbidden)
                finish()
            }
        }
        routing {
            pageRoutes()
            apiRoutes()
            artifactRoutes()
            reportRoutes()
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

    private fun Route.apiRoutes() {
        get("/api/snapshot") {
            call.response.header(HttpHeaders.CacheControl, NO_STORE)
            call.respondText(snapshotJson(), ContentType.Application.Json)
        }
        get("/api/agents/{agentId}") {
            val detail = agentIdOrNull(call.parameters["agentId"])?.let(dashboard::agentDetail)
            if (detail == null) return@get call.notFound()
            call.response.header(HttpHeaders.CacheControl, NO_STORE)
            call.respondText(DashboardJson.detail(detail), ContentType.Application.Json)
        }
        sse("/api/stream") {
            heartbeat { period = HEARTBEAT }
            send(ServerSentEvent(comments = "connected", retry = RETRY_MILLIS))
            dashboard.updates.collect { snapshot ->
                send(ServerSentEvent(data = DashboardJson.snapshot(snapshot, reportReady()), event = "snapshot", id = "${snapshot.version}"))
            }
        }
    }

    private fun Route.artifactRoutes() {
        get("/artifacts/{artifactId}") {
            val id = call.parameters["artifactId"]?.takeIf(ARTIFACT_ID::matches)
            val record = id?.let { dashboard.artifact(ArtifactId(it)) }
            if (record == null) return@get call.notFound()
            call.respondArtifact(record)
        }
        // The report links its evidence relatively (`../a07/0003-screenshot.png`), which resolves to this route.
        get("/{owner}/{file}") {
            val owner = call.parameters["owner"]?.takeIf(OWNER::matches)
            val file = call.parameters["file"]?.takeIf(FILE::matches)
            val record = if (owner == null || file == null) null else dashboard.artifactAt("$owner/$file")
            if (record == null) return@get call.notFound()
            call.respondArtifact(record)
        }
    }

    private fun Route.reportRoutes() {
        get("/report") { call.respondRedirect(DashboardJson.REPORT_URL) }
        get("/report/{path...}") {
            val root = reportDirectory()
            val segments = call.parameters.getAll("path").orEmpty()
            val file = root?.let { withContext(Dispatchers.IO) { ReportFiles.resolve(it, segments) } }
            if (file == null) {
                val message = if (root == null) "Hesabat hələ hazır deyil." else "Tapılmadı."
                return@get call.respondText(message, status = HttpStatusCode.NotFound)
            }
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header("Content-Security-Policy", REPORT_POLICY)
            call.respond(LocalPathContent(file, ReportFiles.contentType(file)))
        }
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private suspend fun snapshotJson(): String = DashboardJson.snapshot(dashboard.snapshot(), reportReady())

    private suspend fun reportReady(): Boolean = withContext(Dispatchers.IO) { reportDirectory()?.isDirectory() == true }

    private suspend fun ApplicationCall.respondArtifact(record: ArtifactRecord) {
        val file =
            withContext(Dispatchers.IO) {
                try {
                    artifacts.resolve(record).takeIf { Files.isRegularFile(it) }
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: return notFound()
        val tag = "\"${record.sha256}\""
        response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        response.header(HttpHeaders.ETag, tag)
        response.header("Content-Security-Policy", ARTIFACT_POLICY)
        if (request.headers[HttpHeaders.IfNoneMatch] == tag) return respond(HttpStatusCode.NotModified)
        respond(LocalPathContent(file, ArtifactContent.type(record.type)))
    }

    private suspend fun ApplicationCall.notFound() {
        response.header(HttpHeaders.CacheControl, NO_STORE)
        respondText("Tapılmadı.", status = HttpStatusCode.NotFound)
    }

    private fun hostAllowed(header: String?): Boolean {
        if (header == null) return true
        val name =
            if (header.startsWith("[")) {
                header.substringBefore(']').removePrefix("[")
            } else {
                header.substringBeforeLast(':').takeIf { header.count { it == ':' } == 1 } ?: header
            }
        return name.lowercase() in allowedHosts
    }

    private companion object {
        const val MAX_PORT = 65_535
        const val GRACE_MILLIS = 100L
        const val TIMEOUT_MILLIS = 2_000L
        const val RETRY_MILLIS = 2_000L
        const val NO_STORE = "no-store"
        val HEARTBEAT = 15.seconds
        val ARTIFACT_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val OWNER = Regex("[A-Za-z0-9_-]{1,64}")
        val FILE = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")
        const val ARTIFACT_POLICY = "default-src 'none'; style-src 'unsafe-inline'; sandbox"
        const val REPORT_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; form-action 'none'; " +
                "frame-ancestors 'none'"

        fun isLoopback(host: String): Boolean =
            try {
                InetAddress.getByName(host.removeSurrounding("[", "]")).isLoopbackAddress
            } catch (_: java.net.UnknownHostException) {
                false
            }

        fun agentIdOrNull(text: String?): AgentId? =
            try {
                text?.let(::AgentId)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
