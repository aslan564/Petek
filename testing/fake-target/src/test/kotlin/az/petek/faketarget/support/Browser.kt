/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.support

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.parameters
import java.net.URI

/**
 * One person's user agent against the fake: its own cookie jar, no automatic redirects (tests assert them), and
 * helpers that submit forms the way a browser would.
 */
class Browser(
    private val baseUrl: URI,
) : AutoCloseable {
    val client =
        HttpClient(CIO) {
            install(HttpCookies)
            install(SSE)
            followRedirects = false
            expectSuccess = false
        }

    fun url(path: String): String = baseUrl.resolve(path).toString()

    suspend fun get(path: String): Page = client.get(url(path)).toPage()

    suspend fun submit(
        path: String,
        vararg fields: Pair<String, String>,
    ): Page =
        client
            .submitForm(
                url = url(path),
                formParameters = parameters { fields.forEach { (name, value) -> append(name, value) } },
            ).toPage()

    /** Submits a form and follows the redirect chain (like a browser), returning the final page. */
    suspend fun submitAndFollow(
        path: String,
        vararg fields: Pair<String, String>,
    ): Page = follow(submit(path, *fields))

    suspend fun follow(page: Page): Page {
        var current = page
        repeat(MAX_REDIRECTS) {
            val location = current.location ?: return current
            current = get(location)
        }
        error("Too many redirects")
    }

    suspend fun api(
        method: HttpMethod,
        path: String,
        json: String? = null,
    ): Page =
        client
            .request(url(path)) {
                this.method = method
                if (json != null) {
                    contentType(ContentType.Application.Json)
                    setBody(json)
                }
            }.toPage()

    suspend fun getWithHeader(
        path: String,
        name: String,
        value: String,
    ): Page = client.get(url(path)) { header(name, value) }.toPage()

    override fun close() = client.close()

    private companion object {
        const val MAX_REDIRECTS = 10
    }
}

suspend fun HttpResponse.toPage(): Page =
    Page(
        status = status.value,
        body = bodyAsText(),
        location = headers[HttpHeaders.Location],
        setCookies = headers.getAll(HttpHeaders.SetCookie).orEmpty(),
        contentType = headers[HttpHeaders.ContentType],
    )
