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

import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.SetupAnswer
import az.petek.dashboard.domain.SiteSetup
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * The page `petek panel` serves when no configuration names a site to test (AGENTS.md rule 12): one question, which
 * site, answered by [setup]. Bound to the loopback interface behind the panel's own guard ([RequestGuard]): local
 * `Host` and `Origin` only, the page's per-process token on every POST, no CORS, and a Content-Security-Policy that
 * allows the page's own nonce-carrying style and script only.
 *
 * Endpoints: `GET /` the question, or a redirect to the panel once the site is set up; `POST /api/setup` with
 * `{"target": "<address>"}`, answered `{"panel": "<url>"}` or 400 with the reason (`error`, as the panel's API does).
 *
 * Start once, stop (or [close]) once; a stopped server cannot be restarted.
 */
class SetupServer(
    private val setup: SiteSetup,
    private val host: String = "127.0.0.1",
    private val port: Int = 7070,
) : AutoCloseable {
    init {
        require(port in 0..MAX_PORT) { "port must be in 0..$MAX_PORT, was $port" }
        require(RequestGuard.isLoopback(host)) { "The setup page binds to the loopback interface only; '$host' is not a loopback address" }
    }

    private val guard = RequestGuard(host)
    private val template: String =
        requireNotNull(SetupServer::class.java.getResource(PAGE)) { "missing $PAGE" }.readText().also { page ->
            check(NONCE_MARK in page && TOKEN_MARK in page) { "the setup page misses its $NONCE_MARK or $TOKEN_MARK placeholder" }
        }
    private val random = SecureRandom()
    private val lock = Any()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var stopped = false

    /** Where the panel runs once the owner's answer was accepted; the page then sends everyone there. */
    @Volatile
    private var panel: String? = null

    /** Starts listening and returns the page's URI; a taken port fails with an [IllegalStateException] naming it. */
    fun start(): URI =
        synchronized(lock) {
            check(server == null && !stopped) { "SetupServer can be started only once" }
            val started =
                try {
                    embeddedServer(CIO, port = port, host = host) { module() }.start(wait = false)
                } catch (e: Exception) {
                    throw IllegalStateException("Cannot start the setup page on $host:$port (is the port already in use?)", e)
                }
            server = started
            val boundPort =
                runBlocking {
                    started.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val name = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            URI("http://$name:$boundPort/")
        }

    /** Stops listening. Idempotent. */
    fun stop() {
        val current =
            synchronized(lock) {
                stopped = true
                server.also { server = null }
            } ?: return
        current.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
    }

    override fun close() = stop()

    private fun Application.module() {
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
                        "Sorğu səhifənin öz açarı olmadan göndərilib."
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
            get("/") {
                call.response.header(HttpHeaders.CacheControl, NO_STORE)
                panel?.let { return@get call.respondRedirect(it) }
                val nonce = nonce()
                call.response.header(
                    "Content-Security-Policy",
                    "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; img-src data:; connect-src 'self'; " +
                        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
                )
                call.respondText(
                    template.replace(NONCE_MARK, nonce).replace(TOKEN_MARK, guard.token),
                    ContentType.Text.Html.withCharset(Charsets.UTF_8),
                )
            }
            post("/api/setup") { answer(call) }
        }
    }

    private suspend fun answer(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, NO_STORE)
        val target = target(call) ?: return call.refuse("Sorğu JSON formatında, `target` sahəsi ilə olmalıdır.")
        val answer =
            try {
                setup.configure(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "The site could not be set up" }
                return call.refuse("Quraşdırma alınmadı: ${e.message ?: e::class.simpleName}", HttpStatusCode.InternalServerError)
            }
        when (answer) {
            is SetupAnswer.Refused -> {
                call.refuse(answer.message)
            }

            is SetupAnswer.Ready -> {
                check(isLocalPanel(answer.panel)) { "the panel must run on the loopback interface, not at ${answer.panel}" }
                panel = answer.panel
                call.respondText(buildJsonObject { put("panel", answer.panel) }.toString(), ContentType.Application.Json)
            }
        }
    }

    /** The `target` of the JSON body, or null when the body is not a small JSON object with that text. */
    private suspend fun target(call: ApplicationCall): String? {
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (!call.request.contentType().match(ContentType.Application.Json) || length == null || length > MAX_BODY_BYTES) return null
        val body = runCatching { Json.parseToJsonElement(call.receiveText()) }.getOrNull() as? JsonObject ?: return null
        return (body["target"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    private suspend fun ApplicationCall.refuse(
        message: String,
        status: HttpStatusCode = HttpStatusCode.BadRequest,
    ) = respondText(PanelJson.error(message, listOf(FieldProblem("target", message))), ContentType.Application.Json, status)

    private fun nonce(): String = Base64.getEncoder().encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes))

    private companion object {
        const val PAGE = "/az/petek/dashboard/infrastructure/setup/index.html"
        const val NONCE_MARK = "{{NONCE}}"
        const val TOKEN_MARK = "{{TOKEN}}"
        const val NONCE_BYTES = 18
        const val MAX_PORT = 65_535
        const val MAX_BODY_BYTES = 4L * 1024
        const val GRACE_MILLIS = 100L
        const val TIMEOUT_MILLIS = 2_000L
        const val NO_STORE = "no-store"
        val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)

        fun isLocalPanel(url: String): Boolean =
            runCatching { URI(url) }
                .getOrNull()
                ?.takeIf { it.scheme == "http" && it.host != null }
                ?.let { RequestGuard.isLoopback(it.host) } == true
    }
}
