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

package az.petek.faketarget.support

import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

/** An open `GET /events` stream of one browser (with its session cookie), collected in the background. */
class LiveStream(
    browser: Browser,
    query: String = "",
    lastEventId: String? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connected = CompletableDeferred<Unit>()
    private val responseHeaders = CompletableDeferred<Headers>()
    private val events = Channel<ServerSentEvent>(Channel.UNLIMITED)
    private val ended = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                browser.client.sse(
                    urlString = browser.url("/events$query"),
                    showCommentEvents = true,
                    request = { lastEventId?.let { header("Last-Event-ID", it) } },
                ) {
                    responseHeaders.complete(call.response.headers)
                    incoming.collect { event ->
                        if (event.comments?.contains("connected") == true) connected.complete(Unit)
                        if (event.event == "notification") events.send(event)
                    }
                }
            } finally {
                ended.complete(Unit)
            }
        }
    }

    /** Suspends until the server ended the stream (or the connection broke). */
    suspend fun awaitEnded() = withTimeout(TIMEOUT) { ended.await() }

    /** Suspends until the server has registered the stream (it sends a `connected` comment right after). */
    suspend fun awaitConnected() = withTimeout(TIMEOUT) { connected.await() }

    suspend fun headers(): Headers = withTimeout(TIMEOUT) { responseHeaders.await() }

    suspend fun awaitEvent(): ServerSentEvent = withTimeout(TIMEOUT) { events.receive() }

    suspend fun awaitNotification(): JsonObject = Json.parseToJsonElement(checkNotNull(awaitEvent().data)).jsonObject

    /** Notifications that already arrived, without waiting. */
    fun drain(): List<ServerSentEvent> = generateSequence { events.tryReceive().getOrNull() }.toList()

    override fun close() = scope.cancel()

    private companion object {
        val TIMEOUT = 5.seconds
    }
}
