/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.diagnostics

import az.petek.faketarget.FakeTargetServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import javax.net.ssl.SSLHandshakeException

class HttpProbeTest {
    private lateinit var target: FakeTargetServer
    private val probe = HttpProbe()

    @BeforeEach
    fun start() {
        target = FakeTargetServer().start()
    }

    @AfterEach
    fun stop() {
        probe.close()
        target.close()
    }

    @Test
    fun `an answer carries its status`() =
        runBlocking<Unit> {
            probe.get(URI("${target.baseUrl}/login")) shouldBe HttpCheck.Answered(200, null)
        }

    @Test
    fun `redirects are reported instead of followed`() =
        runBlocking<Unit> {
            val answer = probe.get(target.baseUrl).shouldBeInstanceOf<HttpCheck.Answered>()

            answer.isRedirect shouldBe true
            answer.location shouldBe "/login?next=%2F"
            answer.toString() shouldBe "HTTP 303 → /login?next=%2F"
        }

    @Test
    fun `headers are sent without appearing in the result`() =
        runBlocking<Unit> {
            val url = URI("${target.baseUrl}/test/otp/%2B994500000000")

            probe.get(url) shouldBe HttpCheck.Answered(401, null)
            val withToken = probe.get(url, mapOf("X-Test-Token" to "dev-token"))

            withToken shouldBe HttpCheck.Answered(404, null)
            withToken.toString() shouldBe "HTTP 404"
        }

    @Test
    fun `a closed port is unreachable with the exception named`() =
        runBlocking<Unit> {
            val check = probe.get(URI("http://127.0.0.1:9/")).shouldBeInstanceOf<HttpCheck.Unreachable>()

            check.error shouldContain "ConnectException"
        }

    @Test
    fun `the cause chain is shown verbatim`() {
        val error = IOException("handshake failed", SSLHandshakeException("PKIX path building failed"))

        HttpProbe.describe(error) shouldBe "IOException: handshake failed (caused by SSLHandshakeException: PKIX path building failed)"
    }
}
