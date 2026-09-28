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

package az.petek.app.panel

import az.petek.app.config.EnvFile
import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.testing.PanelHarness
import az.petek.app.testing.PanelWaits
import az.petek.core.testing.FakeHarnessClock
import az.petek.dashboard.domain.AccountRequest
import az.petek.llm.domain.LlmException
import az.petek.ownership.testing.OwnershipTestKit
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path

/** The setup screen ("Quraşdırma"): the site, its ownership, the AI, each checked when the page asks. */
class PanelReadinessTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()

    @AfterEach
    fun close() {
        open.forEach { it.close() }
    }

    private fun harness(
        ownership: az.petek.ownership.application.SiteOwnership = OwnershipTestKit.owned(FakeHarnessClock()),
        reachability: az.petek.app.diagnostics.TargetReachability = az.petek.app.diagnostics.TargetReachability.ALWAYS,
    ): PanelHarness = PanelHarness(dir, site = PanelWaits.site(), ownership = ownership, reachability = reachability).also { open += it }

    @Test
    fun `the setup screen shows the site and the configured AI without contacting anything`() =
        runBlocking<Unit> {
            val panel = harness()

            val readiness = panel.backend.readiness()

            readiness.target shouldBe "http://127.0.0.1:9"
            readiness.ai.configured shouldBe false
            readiness.ai.provider shouldBe "none"
        }

    @Test
    fun `the site is asked whether it answers, and a site that does not says why`() =
        runBlocking<Unit> {
            harness().backend.checkSite().reachable shouldBe true

            val down = harness(reachability = { TargetAnswer.Unreachable("HTTP 503") }).backend.checkSite()

            down.reachable shouldBe false
            down.detail shouldBe "HTTP 503"
        }

    @Test
    fun `ownership is shown as proved, as local, or with the proof to publish`() =
        runBlocking<Unit> {
            harness().backend.checkOwnership(fresh = false).state shouldBe "VERIFIED"
            harness(ownership = OwnershipTestKit.unowned(FakeHarnessClock(), local = setOf("127.0.0.1")))
                .backend
                .checkOwnership(fresh = true)
                .state shouldBe "EXEMPT"

            val unproved = harness(ownership = OwnershipTestKit.unowned(FakeHarnessClock())).backend.checkOwnership(fresh = true)

            unproved.state shouldBe "UNVERIFIED"
            unproved.proofLine.shouldNotBeNull() shouldStartWith "petek-verification="
            unproved.fileUrl shouldBe "http://127.0.0.1:9/.well-known/petek-verification.txt"
        }

    @Test
    fun `the AI is sent one tiny request and its answer is reported`() =
        runBlocking<Unit> {
            val check = harness().backend.testAi()

            check.ok shouldBe true
            check.detail shouldBe ""
        }

    @Test
    fun `an AI that fails is reported without the secrets its error carries, and clipped`() =
        runBlocking<Unit> {
            val panel = harness()
            val key = "sk-ant-" + "a".repeat(40)
            panel.llm.failure = LlmException.Unavailable("not logged in: key $key was refused. " + "x".repeat(600))

            val check = panel.backend.testAi()

            check.ok shouldBe false
            check.detail shouldContain "not logged in"
            check.detail shouldNotContain key
            (check.detail.length <= 301) shouldBe true
        }

    @Test
    fun `an account added in the panel keeps its password in the configuration file the panel was started with`() =
        runBlocking<Unit> {
            val staging = dir.resolve("staging.env").also { Files.writeString(it, "PETEK_TARGET=http://127.0.0.1:9\n") }
            val panel =
                PanelHarness(dir, site = PanelWaits.site(), configurationFile = staging).also { open += it }

            panel.backend.addAccount(AccountRequest("http://127.0.0.1:9", "admin", "owner@site.example", "s3cret-pass"))

            EnvFile.load(staging).values shouldContain "s3cret-pass"
            Files.exists(dir.resolve(".env")) shouldBe false
            panel.backend.readiness().configuration shouldBe staging.toAbsolutePath().toString()
        }

    @Test
    fun `the page reads the setup through the panel's own API`() =
        runBlocking<Unit> {
            val panel = harness()
            val http = HttpClient.newHttpClient()

            val answer =
                http.send(
                    HttpRequest.newBuilder(URI(panel.panel.url.toString()).resolve("/api/readiness")).GET().build(),
                    BodyHandlers.ofString(),
                )

            answer.statusCode() shouldBe 200
            val body = Json.parseToJsonElement(answer.body()).jsonObject
            body["target"].shouldNotBeNull().jsonPrimitive.content shouldBe "http://127.0.0.1:9"
            answer.body() shouldContain "\"ai\""
        }
}
