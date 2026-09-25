/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure

import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Who may talk to the panel. The server listens on the loopback interface only; on top of that:
 * - the `Host` header must name a loopback host, so a web page elsewhere cannot reach the panel through DNS rebinding;
 * - a browser `Origin` (sent with every cross-origin request and every POST) must be a loopback origin too;
 * - every mutating request carries [token] in [TOKEN_HEADER]. The token is random per process and only written into
 *   the page itself, which other origins cannot read (no CORS), so no other site can start or change anything.
 */
internal class RequestGuard(
    host: String,
    val token: String = newToken(),
) {
    private val allowedHosts = setOf("127.0.0.1", "localhost", "::1", host.lowercase().removeSurrounding("[", "]"))

    /** Requests without a Host header (HTTP/1.0 tools) are local by construction of the loopback bind. */
    fun hostAllowed(header: String?): Boolean = header == null || hostName(header) in allowedHosts

    fun originAllowed(header: String?): Boolean {
        if (header == null) return true
        val host =
            try {
                URI(header).host
            } catch (_: URISyntaxException) {
                null
            }
        return host != null && host.lowercase().removeSurrounding("[", "]") in allowedHosts
    }

    /** Constant-time comparison, so the token cannot be guessed byte by byte from response times. */
    fun tokenValid(header: String?): Boolean =
        header != null && MessageDigest.isEqual(header.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    private fun hostName(header: String): String {
        val name =
            if (header.startsWith("[")) {
                header.substringBefore(']').removePrefix("[")
            } else if (header.count { it == ':' } == 1) {
                header.substringBefore(':')
            } else {
                header
            }
        return name.lowercase()
    }

    companion object {
        const val TOKEN_HEADER = "X-Petek-Token"
        private const val TOKEN_BYTES = 32

        fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
