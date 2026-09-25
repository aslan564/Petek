package az.petek.browser.infrastructure

import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.RealtimeTransport
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Infers how a page receives live updates from the traffic the browser adapter reports to it. The target never has
 * to say which mechanism it uses (docs/ARCHITECTURE.md); the report just shows what was observed:
 *
 * - [RealtimeTransport.WEBSOCKET]: the page opened a WebSocket.
 * - [RealtimeTransport.SSE]: an `EventSource` request, or any response with content type `text/event-stream`.
 * - [RealtimeTransport.POLLING]: the same GET XHR/fetch endpoint was requested at least [minimumPollRequests] times
 *   with roughly regular gaps: every gap is within [intervalTolerance] of the median gap, and the median is at
 *   least [minimumPollInterval] (a burst of simultaneous identical requests is not polling). Endpoints are compared
 *   without their query string, because pollers usually add cursors or cache busters (`?since=…`, `?_=…`).
 *
 * URLs in [NetworkObservation.details] are shown without query string and user info, since those may carry tokens.
 * Request start times come from the browser (see [PlaywrightBrowserSession]), not from the moment the adapter
 * thread happened to process the event, so the measured gaps are real. Thread-safe.
 */
internal class RealtimeTrafficRecorder(
    private val minimumPollRequests: Int = 3,
    private val minimumPollInterval: Duration = 100.milliseconds,
    private val intervalTolerance: Double = 0.5,
    private val maxRememberedRequests: Int = 20,
    private val maxDetailsPerTransport: Int = 10,
) {
    private val lock = Any()
    private val webSockets = LinkedHashSet<String>()
    private val eventStreams = LinkedHashSet<String>()
    private val getRequestStarts = LinkedHashMap<String, MutableList<Double>>()

    fun webSocketOpened(url: String) {
        synchronized(lock) { webSockets += redacted(url) }
    }

    fun eventStreamSeen(url: String) {
        synchronized(lock) { eventStreams += redacted(url) }
    }

    /** An XHR or fetch request that got a response; [startedAtEpochMillis] is its browser-side start time. */
    fun fetchCompleted(
        method: String,
        url: String,
        startedAtEpochMillis: Double,
    ) {
        if (!method.equals("GET", ignoreCase = true)) return
        synchronized(lock) {
            val starts = getRequestStarts.getOrPut(redacted(url)) { mutableListOf() }
            starts += startedAtEpochMillis
            if (starts.size > maxRememberedRequests) starts.removeAt(0)
        }
    }

    fun observation(): NetworkObservation =
        synchronized(lock) {
            val polled = getRequestStarts.mapNotNull { (endpoint, starts) -> polling(endpoint, starts) }
            val transports =
                buildSet {
                    if (webSockets.isNotEmpty()) add(RealtimeTransport.WEBSOCKET)
                    if (eventStreams.isNotEmpty()) add(RealtimeTransport.SSE)
                    if (polled.isNotEmpty()) add(RealtimeTransport.POLLING)
                }
            val details =
                webSockets.take(maxDetailsPerTransport).map { "WebSocket $it" } +
                    eventStreams.take(maxDetailsPerTransport).map { "SSE $it" } +
                    polled.take(maxDetailsPerTransport).map {
                        "Polling GET ${it.endpoint} every ~${it.interval.inWholeMilliseconds} ms (${it.requests} requests)"
                    }
            NetworkObservation(transports, details)
        }

    /** The endpoint's polling statistics when its request [starts] look like polling, otherwise null. */
    private fun polling(
        endpoint: String,
        starts: List<Double>,
    ): PolledEndpoint? {
        if (starts.size < minimumPollRequests) return null
        val gaps = starts.sorted().zipWithNext { earlier, later -> later - earlier }
        val median = gaps.sorted()[gaps.size / 2]
        if (median < minimumPollInterval.inWholeMilliseconds) return null
        val regular = gaps.all { abs(it - median) <= intervalTolerance * median }
        return if (regular) PolledEndpoint(endpoint, median.milliseconds, starts.size) else null
    }

    /** Drops query, fragment and user info, which may carry tokens or credentials. */
    private fun redacted(url: String): String = USER_INFO.replace(url.substringBefore('#').substringBefore('?'), "$1")

    private data class PolledEndpoint(
        val endpoint: String,
        val interval: Duration,
        val requests: Int,
    )

    private companion object {
        val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@")
    }
}
