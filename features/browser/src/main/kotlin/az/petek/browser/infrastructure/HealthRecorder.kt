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

package az.petek.browser.infrastructure

import az.petek.browser.domain.PageHealth
import az.petek.browser.domain.SlowResponse
import az.petek.core.time.HarnessTimestamp
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration

/**
 * What one session's page reported that a user would call broken, for [PlaywrightBrowserSession.health]: console
 * errors and uncaught exceptions, requests to [target]'s origin that failed (status 400 or more, or no answer) and
 * how long each of them took. Other origins (analytics, CDNs) never count. Paths keep no query string (it may carry
 * tokens). Only the latest [capacity] entries of each kind are kept. Thread-safe.
 */
internal class HealthRecorder(
    target: URI,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val origin: String? = originOf(target)
    private val lock = Any()
    private val console = ArrayDeque<Timed<String>>()
    private val failures = ArrayDeque<Timed<String>>()
    private val timings = ArrayDeque<Timed<SlowResponse>>()

    private data class Timed<T>(
        val at: HarnessTimestamp,
        val value: T,
    )

    fun consoleError(
        message: String,
        at: HarnessTimestamp,
    ) = add(console, Timed(at, message.take(MAX_TEXT)))

    fun answered(
        method: String,
        url: String,
        status: Int,
        at: HarnessTimestamp,
    ) {
        val path = pathOnTarget(url) ?: return
        if (status >= FIRST_ERROR) add(failures, Timed(at, "${method.uppercase()} $path -> $status"))
    }

    fun failed(
        method: String,
        url: String,
        error: String?,
        at: HarnessTimestamp,
    ) {
        val path = pathOnTarget(url) ?: return
        add(failures, Timed(at, "${method.uppercase()} $path -> ${error?.take(MAX_TEXT) ?: "no answer"}"))
    }

    fun finished(
        method: String,
        url: String,
        millis: Long,
        at: HarnessTimestamp,
    ) {
        val path = pathOnTarget(url) ?: return
        if (millis >= 0) add(timings, Timed(at, SlowResponse(method.uppercase(), path, millis)))
    }

    fun since(
        since: HarnessTimestamp,
        slowAfter: Duration,
        redact: (String) -> String,
    ): PageHealth =
        synchronized(lock) {
            fun <T> ArrayDeque<Timed<T>>.after() = filter { it.at.monotonicNanos >= since.monotonicNanos }.map { it.value }
            PageHealth(
                consoleErrors = console.after().map(redact),
                failedRequests = failures.after().map(redact),
                slowResponses = timings.after().filter { it.millis >= slowAfter.inWholeMilliseconds },
            )
        }

    private fun <T> add(
        queue: ArrayDeque<Timed<T>>,
        entry: Timed<T>,
    ) = synchronized(lock) {
        queue.addLast(entry)
        while (queue.size > capacity) queue.removeFirst()
    }

    private fun pathOnTarget(url: String): String? {
        val address =
            try {
                URI(url)
            } catch (_: URISyntaxException) {
                return null
            }
        if (origin == null || originOf(address) != origin) return null
        return address.rawPath.orEmpty().ifEmpty { "/" }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
        const val FIRST_ERROR = 400
        const val MAX_TEXT = 300
        private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

        fun originOf(address: URI): String? {
            val scheme = address.scheme?.lowercase() ?: return null
            val host = address.host?.lowercase() ?: return null
            val port = if (address.port >= 0) address.port else DEFAULT_PORTS[scheme] ?: return null
            return "$scheme://$host:$port"
        }
    }
}
