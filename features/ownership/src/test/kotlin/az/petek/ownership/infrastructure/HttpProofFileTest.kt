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

package az.petek.ownership.infrastructure

import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipToken
import az.petek.ownership.domain.ProofLook
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI

class HttpProofFileTest {
    private val token = OwnershipToken("0123456789abcdef0123456789abcdef")
    private val proofLine = "petek-verification=${token.value}"
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val probe = HttpProofFile()

    @AfterEach
    fun stop() {
        probe.close()
        server.stop(0)
    }

    private fun serve(
        path: String,
        handler: (HttpExchange) -> Unit,
    ) {
        server.createContext(path) { exchange -> exchange.use(handler) }
    }

    private fun HttpExchange.reply(
        status: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        val bytes = body.toByteArray()
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.write(bytes)
    }

    private suspend fun look(target: String = "$base/"): ProofLook {
        val url = URI(target)
        return probe.look(url, OwnershipChallenge.of(url, token))
    }

    @Test
    fun `a file with the proof line among others is the proof`() =
        runTest {
            serve(OwnershipChallenge.FILE_PATH) { it.reply(200, "petek-verification=ffffffffffffffffffffffffffffffff\n  $proofLine  \n") }

            look() shouldBe ProofLook.Found(OwnershipMethod.WELL_KNOWN_FILE)
        }

    @Test
    fun `a missing file is reported with its address and status`() =
        runTest {
            val missing = look().shouldBeInstanceOf<ProofLook.Missing>()

            missing.looked.single() shouldBe "$base/.well-known/petek-verification.txt: HTTP 404"
        }

    @Test
    fun `a file without this token proves nothing`() =
        runTest {
            serve(OwnershipChallenge.FILE_PATH) { it.reply(200, "petek-verification=ffffffffffffffffffffffffffffffff") }

            look().shouldBeInstanceOf<ProofLook.Missing>().looked.single() shouldContain "the file has no line $proofLine"
        }

    @Test
    fun `a redirect on the same host is followed`() =
        runTest {
            serve(OwnershipChallenge.FILE_PATH) { it.reply(301, headers = mapOf("Location" to "/moved/proof.txt")) }
            serve("/moved/proof.txt") { it.reply(200, proofLine) }

            look() shouldBe ProofLook.Found(OwnershipMethod.WELL_KNOWN_FILE)
        }

    @Test
    fun `a redirect to another host cannot prove this one`() =
        runTest {
            serve(OwnershipChallenge.FILE_PATH) {
                it.reply(
                    302,
                    headers =
                        mapOf("Location" to "http://localhost:${server.address.port}/elsewhere"),
                )
            }

            look().shouldBeInstanceOf<ProofLook.Missing>().looked.single() shouldContain "to another host (localhost)"
        }

    @Test
    fun `a file larger than 8 KB is not read as a proof`() =
        runTest {
            serve(OwnershipChallenge.FILE_PATH) { it.reply(200, "x".repeat(9000) + "\n" + proofLine) }

            look().shouldBeInstanceOf<ProofLook.Missing>().looked.single() shouldContain "larger than 8192 bytes"
        }

    @Test
    fun `a site that does not answer is a reason, not an exception`() =
        runTest {
            val closedPort = ServerSocket(0).use { it.localPort }

            look("http://127.0.0.1:$closedPort/").shouldBeInstanceOf<ProofLook.Missing>().looked.single() shouldContain
                "http://127.0.0.1:$closedPort/.well-known/petek-verification.txt: "
        }
}
