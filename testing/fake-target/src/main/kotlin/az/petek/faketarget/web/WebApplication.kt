/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

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
