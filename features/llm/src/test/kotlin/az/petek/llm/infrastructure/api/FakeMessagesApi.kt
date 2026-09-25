package az.petek.llm.infrastructure.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** A local stand-in for `POST /v1/messages` that records requests and plays back one canned response. */
class FakeMessagesApi : AutoCloseable {
    data class Recorded(
        val headers: Map<String, String>,
        val body: String,
    )

    data class Canned(
        val status: Int = 200,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
        val delay: Duration = Duration.ZERO,
    )

    val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile
    var next: Canned = Canned(body = "{}")

    private val server: EmbeddedServer<*, *> =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/v1/messages") {
                    val headers =
                        call.request.headers
                            .entries()
                            .associate { (name, values) -> name.lowercase() to values.first() }
                    requests += Recorded(headers, call.receiveText())
                    val canned = next
                    if (canned.delay.isPositive()) delay(canned.delay)
                    canned.headers.forEach { (name, value) -> call.response.header(name, value) }
                    call.respondText(canned.body, ContentType.Application.Json, HttpStatusCode.fromValue(canned.status))
                }
            }
        }.start(wait = false)

    val baseUrl: String = "http://127.0.0.1:${runBlocking {
        server.engine
            .resolvedConnectors()
            .first()
            .port
    }}"

    override fun close() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
}
