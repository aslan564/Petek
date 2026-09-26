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

package az.petek.dashboard.infrastructure

import az.petek.dashboard.domain.SetupAnswer
import az.petek.dashboard.domain.SiteSetup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class SetupServerTest {
    private val asked = CopyOnWriteArrayList<String>()
    private var answer: SetupAnswer = SetupAnswer.Ready("http://127.0.0.1:7071/")
    private val server =
        SetupServer(
            SiteSetup { target ->
                asked += target
                answer
            },
            port = 0,
        )
    private val base = server.start().toString().removeSuffix("/")
    private val client =
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
        }

    @AfterEach
    fun stop() {
        client.close()
        server.close()
    }

    private suspend fun token(): String =
        Regex("""<meta name="petek-token" content="([A-Za-z0-9_-]+)">""")
            .find(client.get("$base/").bodyAsText())
            .shouldNotBeNull()
            .groupValues[1]

    private suspend fun answer(
        body: String,
        token: String?,
        origin: String? = null,
    ): HttpResponse =
        client.post("$base/api/setup") {
            contentType(ContentType.Application.Json)
            if (token != null) header("X-Petek-Token", token)
            if (origin != null) header(HttpHeaders.Origin, origin)
            setBody(body)
        }

    @Test
    fun `the question is served with a nonce-bound policy and the per-process token`() =
        runBlocking<Unit> {
            val response = client.get("$base/")
            val html = response.bodyAsText()

            response.status.value shouldBe 200
            val policy = response.headers["Content-Security-Policy"].shouldNotBeNull()
            val nonce = Regex("'nonce-([^']+)'").find(policy).shouldNotBeNull().groupValues[1]
            html shouldContain "<script nonce=\"$nonce\">"
            html shouldContain "<style nonce=\"$nonce\">"
            html shouldContain "Hansı saytı test edək?"
            html shouldNotContain "{{"
            policy shouldContain "default-src 'none'"
            response.headers["X-Frame-Options"] shouldBe "DENY"
        }

    @Test
    fun `an answer without the page's token or from another site is refused before it reaches the setup`() =
        runBlocking<Unit> {
            val token = token()

            answer("""{"target":"https://staging.example.com"}""", token = null).status.value shouldBe 403
            answer("""{"target":"https://staging.example.com"}""", token = "forged").status.value shouldBe 403
            answer("""{"target":"https://staging.example.com"}""", token, origin = "https://evil.example").status.value shouldBe 403
            asked.shouldBeEmpty()
        }

    @Test
    fun `an accepted answer names the panel, and the page then sends everyone there`() =
        runBlocking<Unit> {
            val response = answer("""{"target":"https://staging.example.com"}""", token())

            response.status.value shouldBe 200
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject["panel"]
                ?.jsonPrimitive
                ?.content shouldBe "http://127.0.0.1:7071/"
            asked shouldContainExactly listOf("https://staging.example.com")
            val again = client.get("$base/")
            again.status.value shouldBe 302
            again.headers[HttpHeaders.Location] shouldBe "http://127.0.0.1:7071/"
        }

    @Test
    fun `a refused answer comes back with its reason, and a malformed one never reaches the setup`() =
        runBlocking<Unit> {
            answer = SetupAnswer.Refused("Sayt cavab vermir.")
            val token = token()

            val refused = answer("""{"target":"https://down.example"}""", token)
            val malformed = answer("""{"url":"https://down.example"}""", token)

            refused.status.value shouldBe 400
            Json
                .parseToJsonElement(refused.bodyAsText())
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content shouldBe "Sayt cavab vermir."
            malformed.status.value shouldBe 400
            asked shouldContainExactly listOf("https://down.example")
            client.get("$base/").status.value shouldBe 200
        }

    @Test
    fun `a panel said to run anywhere but this machine is not followed`() =
        runBlocking<Unit> {
            answer = SetupAnswer.Ready("https://evil.example/")

            answer("""{"target":"https://staging.example.com"}""", token()).status.value shouldBe 500
            client.get("$base/").status.value shouldBe 200
        }

    @Test
    fun `the page binds to the loopback interface only`() {
        shouldThrow<IllegalArgumentException> { SetupServer({ answer }, host = "0.0.0.0", port = 0) }.message shouldContain "loopback"
    }
}
