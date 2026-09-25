/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import az.petek.browser.domain.RealtimeTransport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class RealtimeTrafficRecorderTest {
    private val recorder = RealtimeTrafficRecorder()

    private fun polls(
        url: String,
        vararg startsMillis: Double,
        method: String = "GET",
    ) = startsMillis.forEach { recorder.fetchCompleted(method, url, it) }

    @Test
    fun `no traffic means no transport`() {
        val observation = recorder.observation()

        observation.transports.shouldBeEmpty()
        observation.details.shouldBeEmpty()
    }

    @Test
    fun `a websocket is reported without its query string or credentials`() {
        recorder.webSocketOpened("wss://user:pass@kadrohr.test/socket?token=secret#x")
        recorder.webSocketOpened("wss://kadrohr.test/socket?token=other")

        val observation = recorder.observation()

        observation.transports shouldBe setOf(RealtimeTransport.WEBSOCKET)
        observation.details shouldContainExactly listOf("WebSocket wss://kadrohr.test/socket")
    }

    @Test
    fun `an event stream is reported once per endpoint`() {
        recorder.eventStreamSeen("http://kadrohr.test/events?since=1")
        recorder.eventStreamSeen("http://kadrohr.test/events?since=2")

        recorder.observation().details shouldContainExactly listOf("SSE http://kadrohr.test/events")
    }

    @Test
    fun `regular GET requests to one endpoint are polling even with changing query strings`() {
        polls("http://t/api/news?since=1", 1_000.0)
        polls("http://t/api/news?since=2", 2_020.0)
        polls("http://t/api/news?since=3", 2_990.0)
        polls("http://t/api/news?since=4", 4_010.0)

        val observation = recorder.observation()

        observation.transports shouldBe setOf(RealtimeTransport.POLLING)
        observation.details shouldContainExactly listOf("Polling GET http://t/api/news every ~1020 ms (4 requests)")
    }

    @Test
    fun `two requests are not enough to call it polling`() {
        polls("http://t/api/news", 1_000.0, 2_000.0)

        recorder.observation().transports.shouldBeEmpty()
    }

    @Test
    fun `irregular requests are not polling`() {
        polls("http://t/api/search", 0.0, 100.0, 2_000.0, 2_150.0)

        recorder.observation().transports.shouldBeEmpty()
    }

    @Test
    fun `a burst of identical requests is not polling`() {
        polls("http://t/api/config", 0.0, 5.0, 10.0, 15.0)

        recorder.observation().transports.shouldBeEmpty()
    }

    @Test
    fun `repeated POST requests are not polling`() {
        polls("http://t/api/track", 0.0, 1_000.0, 2_000.0, 3_000.0, method = "POST")

        recorder.observation().transports.shouldBeEmpty()
    }

    @Test
    fun `requests are ordered by start time whatever order they complete in`() {
        polls("http://t/api/poll", 3_000.0, 1_000.0, 2_000.0)

        recorder.observation().transports shouldBe setOf(RealtimeTransport.POLLING)
    }

    @Test
    fun `only the most recent requests of an endpoint are judged`() {
        polls("http://t/api/poll", 0.0, 7.0, 9.0)
        polls("http://t/api/poll", *(1..20).map { 10_000.0 + it * 500.0 }.toDoubleArray())

        recorder.observation().details shouldContainExactly listOf("Polling GET http://t/api/poll every ~500 ms (20 requests)")
    }

    @Test
    fun `all transports seen are reported together`() {
        recorder.webSocketOpened("ws://t/ws")
        recorder.eventStreamSeen("http://t/sse")
        polls("http://t/poll", 0.0, 300.0, 600.0)

        val observation = recorder.observation()

        observation.transports shouldBe setOf(RealtimeTransport.WEBSOCKET, RealtimeTransport.SSE, RealtimeTransport.POLLING)
        observation.details shouldContainExactly
            listOf("WebSocket ws://t/ws", "SSE http://t/sse", "Polling GET http://t/poll every ~300 ms (3 requests)")
    }

    @Test
    fun `concurrent reports are all counted`() =
        runBlocking<Unit> {
            (0 until 20)
                .map { i -> async(Dispatchers.Default) { recorder.fetchCompleted("GET", "http://t/p", i * 250.0) } }
                .awaitAll()

            recorder.observation().details shouldContainExactly listOf("Polling GET http://t/p every ~250 ms (20 requests)")
        }
}
