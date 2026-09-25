package az.petek.faketarget.web

import az.petek.faketarget.api.ErrorJson
import az.petek.faketarget.api.TestApi
import az.petek.faketarget.api.TicketApi
import az.petek.faketarget.service.Failure
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/** Wires the web application: plugins, the page routes, the JSON API, the test API and the SSE stream. */
internal class WebApplication(
    private val auth: AuthRoutes,
    private val app: AppRoutes,
    private val events: EventsRoute,
    private val ticketApi: TicketApi,
    private val testApi: TestApi,
    private val json: Json,
) {
    fun install(application: Application) {
        application.install(ContentNegotiation) { json(json) }
        application.install(SSE)
        application.install(StatusPages) {
            exception<BadRequestException> { call, _ -> call.respondProblem(HttpStatusCode.BadRequest, Failure.INVALID_REQUEST.message) }
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
            respondHtml(status) { errorPage(chrome = null, title = status.description, message = message) }
        }
    }
}
