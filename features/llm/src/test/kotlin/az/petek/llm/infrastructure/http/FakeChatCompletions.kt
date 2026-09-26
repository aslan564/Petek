/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.http

import az.petek.llm.infrastructure.api.FakeMessagesApi
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** A local `chat/completions` endpoint that records requests and plays back queued answers (the last one repeats). */
class FakeChatCompletions : AutoCloseable {
    data class Recorded(
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    val requests = CopyOnWriteArrayList<Recorded>()
    private val queue = ConcurrentLinkedQueue<FakeMessagesApi.Canned>()

    @Volatile
    private var last: FakeMessagesApi.Canned = FakeMessagesApi.Canned(body = "{}")

    fun answer(vararg canned: FakeMessagesApi.Canned) {
        queue.addAll(canned)
    }

    private val server: EmbeddedServer<*, *> =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                post("{path...}") {
                    val headers =
                        call.request.headers
                            .entries()
                            .associate { (name, values) -> name.lowercase() to values.first() }
                    requests += Recorded(call.request.uri, headers, call.receiveText())
                    val canned = queue.poll()?.also { last = it } ?: last
                    canned.headers.forEach { (name, value) -> call.response.header(name, value) }
                    call.respondText(canned.body, ContentType.Application.Json, HttpStatusCode.fromValue(canned.status))
                }
            }
        }.start(wait = false)

    val baseUrl: String = "http://127.0.0.1:${runBlocking {
        server.engine
            .resolvedConnectors()
            .first()
            .port
    }}"

    override fun close() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
}
