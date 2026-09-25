/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import az.petek.browser.domain.ObservedMutation
import az.petek.core.time.HarnessTimestamp
import java.net.URI
import java.net.URISyntaxException

/**
 * The mutating requests one session's page sent to the target and the answers it got, for
 * [PlaywrightBrowserSession.mutations]. The response handler (on the session thread) reports every answer; only those
 * to [target]'s origin (scheme, host and port, default ports made explicit) with a method in [ObservedMutation.METHODS]
 * are kept. Anything else (third-party beacons, page loads, assets) is dropped here, so a race verdict never rests
 * on a request that did not go to the site under test.
 *
 * Paths are stored without query string and fragment, which may carry tokens. A page that posts in a loop cannot
 * exhaust memory: only the latest [capacity] mutations are kept, far more than one step makes. Thread-safe.
 */
internal class MutationRecorder(
    target: URI,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity >= 1) { "capacity must be at least 1, was $capacity" }
    }

    private val origin: Origin? = Origin.of(target)
    private val lock = Any()
    private val recorded = ArrayDeque<ObservedMutation>()

    /** One answer the page got; [url] is the request's absolute URL and [method] its HTTP method. */
    fun responded(
        method: String,
        url: String,
        status: Int,
        at: HarnessTimestamp,
    ) {
        val verb = method.uppercase()
        if (verb !in ObservedMutation.METHODS) return
        val address = parse(url) ?: return
        if (origin == null || Origin.of(address) != origin) return
        val mutation = ObservedMutation(verb, address.rawPath.orEmpty().ifEmpty { "/" }, status, at)
        synchronized(lock) {
            recorded.addLast(mutation)
            while (recorded.size > capacity) recorded.removeFirst()
        }
    }

    /** The kept mutations seen at or after [since] (harness monotonic time), oldest first. */
    fun since(since: HarnessTimestamp): List<ObservedMutation> =
        synchronized(lock) { recorded.filter { it.at.monotonicNanos >= since.monotonicNanos } }

    private fun parse(url: String): URI? =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            null
        }

    /** Scheme, host and port as the browser compares them: case-insensitive, default port made explicit. */
    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
    ) {
        companion object {
            private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

            fun of(address: URI): Origin? {
                val scheme = address.scheme?.lowercase() ?: return null
                val host = address.host?.lowercase() ?: return null
                val port = if (address.port >= 0) address.port else DEFAULT_PORTS[scheme] ?: return null
                return Origin(scheme, host, port)
            }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
