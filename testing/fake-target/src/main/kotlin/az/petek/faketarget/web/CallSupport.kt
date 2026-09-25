/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.web

import az.petek.faketarget.service.Failure
import az.petek.faketarget.service.FailureKind
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.renderSetCookieHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.date.GMTDate

/**
 * The session cookie: an opaque random token (server-side session), `HttpOnly`, `SameSite=Lax`, whole site.
 * The name is part of the fake's contract (`fake_session`).
 */
internal object SessionCookie {
    const val NAME = "fake_session"
    private val ATTRIBUTES = mapOf("SameSite" to "Lax")

    fun read(call: ApplicationCall): String? = call.request.cookies[NAME, CookieEncoding.RAW]?.takeIf { it.isNotBlank() }

    fun write(
        call: ApplicationCall,
        token: String,
    ) {
        call.setCookie(token, maxAge = null, expires = null)
    }

    fun clear(call: ApplicationCall) {
        call.setCookie("", maxAge = 0, expires = GMTDate.START)
    }

    /** Rendered by hand because Ktor's default rendering appends a non-standard encoding attribute. */
    private fun ApplicationCall.setCookie(
        value: String,
        maxAge: Int?,
        expires: GMTDate?,
    ) {
        val header =
            renderSetCookieHeader(
                name = NAME,
                value = value,
                encoding = CookieEncoding.RAW,
                maxAge = maxAge,
                expires = expires,
                path = "/",
                httpOnly = true,
                extensions = ATTRIBUTES,
                includeEncoding = false,
            )
        response.headers.append(HttpHeaders.SetCookie, header)
    }
}

/** POST-redirect-GET. */
internal suspend fun ApplicationCall.seeOther(location: String) {
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode.SeeOther)
}

/** Links in e-mails point where the caller reached the fake (its `Host`), so cookies and links share one origin. */
internal fun ApplicationCall.publicBaseUrl(): String {
    val origin = request.origin
    val host = request.headers[HttpHeaders.Host] ?: "${origin.serverHost}:${origin.serverPort}"
    return "${origin.scheme}://$host"
}

internal fun verifyLocation(email: String): String = "/verify?email=${email.encodeURLParameter()}"

internal fun verifyPhoneLocation(email: String): String = "/verify/phone?email=${email.encodeURLParameter()}"

/**
 * Only same-site paths are accepted as a post-login target. Whitespace and control characters are refused too:
 * browsers strip tabs and newlines from a `Location`, so `/\t/evil.example` would otherwise become `//evil.example`.
 */
internal fun safeNext(next: String?): String? =
    next?.takeIf { path ->
        path.startsWith("/") && !path.startsWith("//") && path.none { it == '\\' || it.isWhitespace() || it.isISOControl() }
    }

internal val Failure.status: HttpStatusCode
    get() =
        when (kind) {
            FailureKind.INVALID -> HttpStatusCode.BadRequest
            FailureKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
            FailureKind.FORBIDDEN -> HttpStatusCode.Forbidden
            FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
            FailureKind.CONFLICT -> HttpStatusCode.Conflict
        }
