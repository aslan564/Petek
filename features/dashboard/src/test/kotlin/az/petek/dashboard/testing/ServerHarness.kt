package az.petek.dashboard.testing

import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.application.UnavailablePanelBackend
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.infrastructure.DashboardServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.nio.file.Path

/**
 * A started [DashboardServer] on a free loopback port with a Ktor client, for endpoint tests. Close it after the test.
 * [raw] sends a request byte for byte (no client-side path normalisation), for traversal and Host-header checks.
 */
class ServerHarness(
    val dashboard: LiveDashboard,
    root: Path,
    backend: PanelBackend = UnavailablePanelBackend(),
    reportDirectory: (() -> Path?)? = null,
) : AutoCloseable {
    val artifacts = TempDirArtifactStore(root)
    val server =
        if (reportDirectory == null) {
            DashboardServer(dashboard, artifacts, port = 0, backend = backend)
        } else {
            DashboardServer(dashboard, artifacts, reportDirectory, port = 0, backend = backend)
        }
    val uri: URI = server.start()
    val base: String = uri.toString().removeSuffix("/")
    val client =
        HttpClient(CIO) {
            install(SSE)
            expectSuccess = false
            followRedirects = false
        }

    /** The per-process token the page carries in its `<meta name="petek-token">`. */
    suspend fun token(): String {
        val html = client.get("$base/").bodyAsText()
        return checkNotNull(TOKEN.find(html)) { "the page has no token" }.groupValues[1]
    }

    suspend fun get(path: String): HttpResponse = client.get(base + path)

    suspend fun post(
        path: String,
        json: String = "{}",
        token: String? = null,
    ): HttpResponse =
        client.post(base + path) {
            contentType(ContentType.Application.Json)
            if (token != null) header("X-Petek-Token", token)
            setBody(json)
        }

    /** Status code and body of a GET sent exactly as written, with an optional Host header. */
    fun raw(
        path: String,
        host: String? = "127.0.0.1:${uri.port}",
        extraHeaders: List<String> = emptyList(),
    ): Pair<Int, String> =
        Socket(uri.host, uri.port).use { socket ->
            val request =
                buildString {
                    append("GET $path HTTP/1.1\r\n")
                    if (host != null) append("Host: $host\r\n")
                    extraHeaders.forEach { append(it).append("\r\n") }
                    append("Connection: close\r\n\r\n")
                }
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val status = reader.readLine().split(' ')[1].toInt()
            val body = reader.readText().substringAfter("\r\n\r\n")
            status to body
        }

    override fun close() {
        client.close()
        server.stop()
    }

    private companion object {
        val TOKEN = Regex("""<meta name="petek-token" content="([A-Za-z0-9_-]+)">""")
    }
}
