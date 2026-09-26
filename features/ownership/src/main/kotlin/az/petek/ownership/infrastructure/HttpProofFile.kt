/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.infrastructure

import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipProbe
import az.petek.ownership.domain.ProofLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Looks for the proof file `/.well-known/petek-verification.txt` on the target's own scheme, host and port with one
 * anonymous GET (the JDK client, HTTP/1.1, bounded time). Redirects are followed only while they stay on the same host
 * (`http` → `https`, a trailing slash): a file served by another host proves nothing about this one. The first
 * [MAX_BYTES] of a `200` answer are read; any line equal to [OwnershipChallenge.proofLine] (surrounding spaces ignored)
 * is the proof, so one file can carry the tokens of several machines. TLS certificates are verified like a browser
 * would; a stage with a self-signed certificate uses the DNS record instead.
 */
class HttpProofFile(
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : OwnershipProbe,
    AutoCloseable {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(minOf(timeout, CONNECT_TIMEOUT).toJavaDuration())
            .build()

    override suspend fun look(
        target: URI,
        challenge: OwnershipChallenge,
    ): ProofLook {
        var url = challenge.fileUrl
        repeat(MAX_REDIRECTS + 1) {
            val answer =
                try {
                    get(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return missing(url, describe(e))
                }
            answer.body.use { body ->
                when {
                    answer.status in REDIRECTS -> {
                        val next =
                            answer.location?.let { resolve(url, it) }
                                ?: return missing(url, "HTTP ${answer.status} without a usable Location")
                        if (!next.host.equals(challenge.fileUrl.host, ignoreCase = true)) {
                            return missing(url, "HTTP ${answer.status} to another host (${next.host}), which cannot prove this one")
                        }
                        url = next
                    }

                    answer.status != OK -> {
                        return missing(url, "HTTP ${answer.status}")
                    }

                    else -> {
                        val bytes = withContext(Dispatchers.IO) { body.readNBytes(MAX_BYTES + 1) }
                        if (bytes.size > MAX_BYTES) return missing(url, "the file is larger than $MAX_BYTES bytes")
                        val lines = bytes.toString(Charsets.UTF_8).lineSequence().map { it.trim() }
                        return if (lines.any { it == challenge.proofLine }) {
                            ProofLook.Found(OwnershipMethod.WELL_KNOWN_FILE)
                        } else {
                            missing(url, "the file has no line ${challenge.proofLine}")
                        }
                    }
                }
            }
        }
        return missing(challenge.fileUrl, "more than $MAX_REDIRECTS redirects")
    }

    override fun close() {
        client.close()
    }

    private suspend fun get(url: URI): Answer {
        val request =
            HttpRequest
                .newBuilder(url)
                .GET()
                .timeout(timeout.toJavaDuration())
                .header("Accept", "text/plain")
                .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        return Answer(response.statusCode(), response.headers().firstValue("Location").orElse(null), response.body())
    }

    private class Answer(
        val status: Int,
        val location: String?,
        val body: InputStream,
    )

    private companion object {
        val DEFAULT_TIMEOUT = 10.seconds
        val CONNECT_TIMEOUT = 5.seconds
        const val MAX_REDIRECTS = 3
        const val MAX_BYTES = 8192
        const val OK = 200
        val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun missing(
            url: URI,
            what: String,
        ) = ProofLook.Missing(listOf("$url: $what"))

        fun resolve(
            base: URI,
            location: String,
        ): URI? =
            try {
                base.resolve(location.trim()).takeIf { it.scheme?.lowercase() in setOf("http", "https") && it.host != null }
            } catch (_: IllegalArgumentException) {
                null
            }

        /** The exception chain as the user must see it (DNS, firewall, TLS, timeout). */
        fun describe(error: Throwable): String =
            generateSequence(error) { it.cause }
                .take(3)
                .joinToString(": ") { it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "error" }
    }
}
