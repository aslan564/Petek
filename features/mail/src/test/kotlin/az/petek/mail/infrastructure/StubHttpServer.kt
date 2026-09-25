/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.infrastructure

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** One request as the server saw it: raw (still encoded) [uri] and [path], decoded [query]. */
data class RecordedRequest(
    val method: String,
    val uri: String,
    val path: String,
    val query: Map<String, List<String>>,
    val headers: Map<String, String>,
    val body: String,
)

data class StubResponse(
    val status: Int = 200,
    val body: String = "",
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
)

/** Embedded Ktor CIO server on a free local port that records every request and answers through [handler]. */
class StubHttpServer(
    private val handler: suspend (RecordedRequest) -> StubResponse,
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val server =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                route("{...}") {
                    handle {
                        val request =
                            RecordedRequest(
                                method = call.request.httpMethod.value,
                                uri = call.request.uri,
                                path = call.request.path(),
                                query =
                                    call.request.queryParameters
                                        .entries()
                                        .associate { it.key to it.value },
                                headers =
                                    call.request.headers
                                        .entries()
                                        .associate { it.key.lowercase() to it.value.joinToString(",") },
                                body = call.receiveText(),
                            )
                        requests += request
                        val response = handler(request)
                        response.headers.forEach { (name, value) -> call.response.headers.append(name, value) }
                        if (response.status == HttpStatusCode.NoContent.value) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respondText(
                                response.body,
                                ContentType.parse(response.contentType),
                                HttpStatusCode.fromValue(response.status),
                            )
                        }
                    }
                }
            }
        }.start(wait = false)

    val baseUrl: URI = URI("http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors() }.first().port}")

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
}
