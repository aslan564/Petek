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
            val answer = probe.get(URI("${target.baseUrl}/login")).shouldBeInstanceOf<HttpCheck.Answered>()

            answer.status shouldBe 200
            answer.location shouldBe null
            answer.bodyStart.isNotEmpty() shouldBe true
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

            probe.get(url).shouldBeInstanceOf<HttpCheck.Answered>().status shouldBe 401
            val withToken = probe.get(url, mapOf("X-Test-Token" to "dev-token")).shouldBeInstanceOf<HttpCheck.Answered>()

            withToken.status shouldBe 404
            withToken.toString() shouldBe "HTTP 404"
            withToken.headers.values.any { "dev-token" in it } shouldBe false
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
