package az.petek.faketarget.web

import az.petek.faketarget.api.ErrorJson
import az.petek.faketarget.api.toJson
import az.petek.faketarget.model.Notification
import az.petek.faketarget.service.AccountService
import az.petek.faketarget.service.Failure
import az.petek.faketarget.service.NotificationService
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.heartbeat
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * `GET /events`: the user's notifications as Server-Sent Events (`event: notification`, `id: n<seq>`, JSON data).
 * The stream first replays what the client missed after `Last-Event-ID` (sent by EventSource on reconnect) or
 * `?after=` (the newest id the page rendered), then stays open for live ones. Without a session: `401`.
 */
internal class EventsRoute(
    private val accounts: AccountService,
    private val notifications: NotificationService,
    private val json: Json,
) {
    fun install(route: Route) {
        route.get("/events") {
            val user = accounts.userBySession(SessionCookie.read(call))
            if (user == null) {
                call.respond(Failure.NOT_LOGGED_IN.status, ErrorJson(Failure.NOT_LOGGED_IN))
                return@get
            }
            val after =
                NotificationService.sequenceOf(call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["after"])
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Accel-Buffering", "no")
            call.respond(
                SSEServerContent(call) {
                    heartbeat { period = HEARTBEAT }
                    val stream = notifications.open(user.email, after)
                    stream.subscription.use { subscription ->
                        stream.backlog.forEach { send(it.toEvent()) }
                        // Marks "backlog delivered, now live" and makes the stream visibly open even when nothing is pending.
                        send(ServerSentEvent(comments = "connected", retry = RETRY_MILLIS))
                        for (notification in subscription.notifications) send(notification.toEvent())
                    }
                },
            )
        }
    }

    private fun Notification.toEvent() = ServerSentEvent(data = json.encodeToString(toJson()), event = "notification", id = id)

    private companion object {
        val HEARTBEAT = 15.seconds
        const val RETRY_MILLIS = 1_000L
    }
}
