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

package az.petek.faketarget.web

import az.petek.faketarget.api.ErrorJson
import az.petek.faketarget.api.TestApi
import az.petek.faketarget.api.TicketApi
import az.petek.faketarget.service.Failure
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.serialization.json.Json
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * The client went away while the server was still writing (an `EventSource` closed with its page, a browser context
 * torn down at the end of a run): Ktor surfaces it as a closed write channel or a broken pipe somewhere in the cause
 * chain. Such a request did not fail, so it is not an error worth a stack trace.
 */
internal fun Throwable.isClientDisconnect(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is ClosedWriteChannelException ||
            it is ChannelWriteException ||
            (it is IOException && it.message?.contains("Broken pipe", ignoreCase = true) == true)
    }

/**
 * Wires the web application: plugins, the page routes, the JSON API, the test API and the SSE stream.
 *
 * Every response is `Cache-Control: no-store`: pages change with every action (ticket status, notification count),
 * and browsers otherwise serve history navigations (Back) from their cache, showing agents a stale state.
 */
internal class WebApplication(
    private val auth: AuthRoutes,
    private val app: AppRoutes,
    private val events: EventsRoute,
    private val ticketApi: TicketApi,
    private val testApi: TestApi,
    private val json: Json,
) {
    fun install(application: Application) {
        application.intercept(ApplicationCallPipeline.Plugins) { context.response.header(HttpHeaders.CacheControl, "no-store") }
        application.install(ContentNegotiation) { json(json) }
        application.install(SSE)
        application.install(StatusPages) {
            exception<BadRequestException> { call, _ -> call.respondProblem(HttpStatusCode.BadRequest, Failure.INVALID_REQUEST.message) }
            exception<ContentTransformationException> { call, _ ->
                call.respondProblem(HttpStatusCode.UnsupportedMediaType, Failure.INVALID_REQUEST.message)
            }
            exception<Throwable> { call, cause ->
                if (cause.isClientDisconnect()) {
                    // A browser closed its SSE stream or left mid-response: nothing failed, and nobody is listening.
                    logger.debug { "Client left ${call.request.path()} (${cause::class.simpleName})" }
                    return@exception
                }
                logger.error(cause) { "Request ${call.request.path()} failed" }
                call.respondProblem(HttpStatusCode.InternalServerError, "Daxili xəta baş verdi.")
            }
        }
        application.routing {
            get("/healthz") { call.respondText("ok") }
            auth.install(this)
            app.install(this)
            events.install(this)
            ticketApi.install(this)
            testApi.install(this)
            route("{...}") { handle { call.respondProblem(HttpStatusCode.NotFound, "Səhifə tapılmadı.") } }
        }
    }

    private suspend fun ApplicationCall.respondProblem(
        status: HttpStatusCode,
        message: String,
    ) {
        val path = request.path()
        if (path.startsWith("/api/") || path.startsWith("/test/") || path == "/events") {
            respond(status, ErrorJson(status.description.lowercase().replace(' ', '_'), message))
        } else {
            // A logged-in visitor keeps the session header and notification panel even on error pages.
            val chrome = runCatching { app.chromeOf(this) }.getOrNull()
            respondHtml(status) { errorPage(chrome, title = status.description, message = message) }
        }
    }
}
