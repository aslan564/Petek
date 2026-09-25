package az.petek.faketarget.api

import az.petek.faketarget.model.Ticket
import az.petek.faketarget.model.User
import az.petek.faketarget.service.AccountService
import az.petek.faketarget.service.Failure
import az.petek.faketarget.service.Outcome
import az.petek.faketarget.service.TicketService
import az.petek.faketarget.web.SessionCookie
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json

/**
 * The regular JSON API used by `http_status` assertions, authenticated by the same session cookie as the pages:
 * `200` ok, `401` no session, `403` not allowed, `404` no such ticket in the user's company, `409` already decided.
 */
internal class TicketApi(
    private val accounts: AccountService,
    private val tickets: TicketService,
    private val json: Json,
) {
    fun install(route: Route) {
        route.route("/api") {
            get("/me") { authenticated { user -> call.respond(user.toMeJson()) } }
            get("/tickets/{id}") { ticketCall { user, id -> tickets.find(user, id) } }
            post("/tickets/{id}/approve") { ticketCall { user, id -> tickets.approve(user, id) } }
            post("/tickets/{id}/reject") { ticketCall { user, id -> tickets.reject(user, id) } }
            post("/tickets/{id}/in-progress") { ticketCall { user, id -> tickets.startProgress(user, id) } }
            post("/tickets/{id}/assign") {
                authenticated { user ->
                    val email = assigneeEmail() ?: return@authenticated call.respondError(Failure.INVALID_REQUEST)
                    respondTicket(tickets.assign(user, call.parameters["id"].orEmpty(), email))
                }
            }
            route("{...}") { handle { call.respond(HttpStatusCode.NotFound, ErrorJson("not_found", "Unknown API endpoint")) } }
        }
    }

    private suspend fun RoutingContext.ticketCall(action: suspend (User, String) -> Outcome<Ticket>) {
        authenticated { user -> respondTicket(action(user, call.parameters["id"].orEmpty())) }
    }

    private suspend fun RoutingContext.authenticated(block: suspend RoutingContext.(User) -> Unit) {
        val user = accounts.userBySession(SessionCookie.read(call)) ?: return call.respondError(Failure.NOT_LOGGED_IN)
        block(user)
    }

    private suspend fun RoutingContext.respondTicket(outcome: Outcome<Ticket>) {
        when (outcome) {
            is Outcome.Ok -> call.respond(outcome.value.toJson())
            is Outcome.Failed -> call.respondError(outcome.failure)
        }
    }

    /** `{"email": "…"}` as JSON (any content type) or `email=…` as a form; null when the body is malformed. */
    private suspend fun RoutingContext.assigneeEmail(): String? {
        val body = call.receiveText()
        if (call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
            return body.parseUrlEncodedParameters()["email"].orEmpty()
        }
        if (body.isBlank()) return ""
        return try {
            json.decodeFromString<AssignRequestJson>(body).email
        } catch (_: IllegalArgumentException) {
            // Also covers kotlinx.serialization's SerializationException.
            null
        }
    }
}
