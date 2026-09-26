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

import az.petek.core.ids.StepId
import az.petek.core.testing.FakeHarnessClock
import az.petek.dashboard.application.DashboardEvidenceRecorder
import az.petek.dashboard.application.DashboardIdentityRepository
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.testing.Records.OTHER_RUN
import az.petek.dashboard.testing.Records.RUN
import az.petek.dashboard.testing.Records.identity
import az.petek.dashboard.testing.Records.status
import az.petek.dashboard.testing.Records.step
import az.petek.dashboard.testing.ServerHarness
import az.petek.dashboard.testing.TempDirArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.testing.InMemoryIdentityRepository
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class DashboardServerTest {
    @TempDir
    lateinit var dir: Path

    private val clock = FakeHarnessClock()
    private val dashboard = LiveDashboard(clock)
    private val evidence = InMemoryEvidence()
    private val recorder = DashboardEvidenceRecorder(evidence, dashboard)
    private val harnesses = mutableListOf<ServerHarness>()

    private fun serve(reportDirectory: (() -> Path?)? = null) =
        ServerHarness(dashboard, dir.resolve("evidence"), reportDirectory = reportDirectory).also {
            harnesses +=
                it
        }

    @AfterEach
    fun stop() = harnesses.forEach { it.close() }

    private suspend fun runWithScreenshot(harness: ServerHarness) =
        run {
            dashboard.runStarted(RUN, listOf(status(1), status(2, AgentState.WORKING, "announce", "click [12]")))
            recorder.step(step(2))
            val shot = harness.artifacts.write(RUN, StepId("stp_shot"), "a02", ArtifactType.SCREENSHOT, PNG)
            recorder.artifact(shot)
            shot
        }

    @Test
    fun `the page is served with a nonce-bound policy and the per-process token`() =
        runBlocking<Unit> {
            val harness = serve()

            val response = harness.get("/")
            val html = response.bodyAsText()

            response.status.value shouldBe 200
            response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            response.headers["X-Content-Type-Options"] shouldBe "nosniff"
            response.headers["X-Frame-Options"] shouldBe "DENY"
            val policy = response.headers["Content-Security-Policy"].shouldNotBeNull()
            val nonce = Regex("'nonce-([^']+)'").find(policy).shouldNotBeNull().groupValues[1]
            html shouldContain "<script nonce=\"$nonce\">"
            html shouldContain "<style nonce=\"$nonce\">"
            html shouldNotContain "{{"
            policy shouldContain "default-src 'none'"
            policy shouldNotContain "unsafe-inline"
            harness.token().length shouldBe 43
            val second = harness.get("/").headers["Content-Security-Policy"].shouldNotBeNull()
            second shouldNotBe policy
        }

    @Test
    fun `the page names the default target escaped, and none when the server has none`() =
        runBlocking<Unit> {
            val artifacts = TempDirArtifactStore(dir.resolve("evidence"))
            val server = DashboardServer(dashboard, artifacts, port = 0, defaultTarget = "https://portal.test/?a=1&b=\"<x>\"")
            try {
                val base = server.start().toString().removeSuffix("/")
                val html = serve().client.get(base + "/").bodyAsText()

                html shouldContain "<meta name=\"petek-target\" content=\"https://portal.test/?a=1&amp;b=&quot;&lt;x&gt;&quot;\">"
            } finally {
                server.stop()
            }
            serve().get("/").bodyAsText() shouldContain "<meta name=\"petek-target\" content=\"\">"
        }

    @Test
    fun `the snapshot is JSON of the board without any contact data or secret`() =
        runBlocking<Unit> {
            val harness = serve()
            val identities = listOf(identity(1), identity(2))
            DashboardIdentityRepository(
                InMemoryIdentityRepository(),
                dashboard,
            ).replaceAll(
                RUN,
                IdentityPlan(
                    az.petek.core.ids
                        .RunTag("k7x2"),
                    identities,
                ),
            )
            runWithScreenshot(harness)

            val body = harness.get("/api/snapshot").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject

            json["run"]!!.jsonObject["runId"]!!.jsonPrimitive.content shouldBe RUN.value
            json["run"]!!.jsonObject["phase"]!!.jsonPrimitive.content shouldBe "RUNNING"
            json["agents"]!!.jsonArray.size shouldBe 2
            json["agents"]!!
                .jsonArray[1]
                .jsonObject["state"]!!
                .jsonPrimitive.content shouldBe "WORKING"
            json["counters"]!!.jsonObject["stepsPassed"]!!.jsonPrimitive.int shouldBe 1
            identities.forEach {
                body shouldNotContain it.email
                body shouldNotContain it.phone
                body shouldNotContain it.password.reveal()
            }
        }

    @Test
    fun `one agent's detail is served and unknown or malformed ids are not found`() =
        runBlocking<Unit> {
            val harness = serve()
            runWithScreenshot(harness)

            val detail = Json.parseToJsonElement(harness.get("/api/agents/a02").bodyAsText()).jsonObject

            detail["agent"]!!.jsonObject["id"]!!.jsonPrimitive.content shouldBe "a02"
            detail["timeline"]!!.jsonArray.size shouldBe 1
            harness.get("/api/agents/a09").status.value shouldBe 404
            harness.get("/api/agents/..%2F..%2Fetc").status.value shouldBe 404
            harness.get("/api/agents/x").status.value shouldBe 404
        }

    @Test
    fun `the stream sends the current board at once and every later change`() =
        runBlocking<Unit> {
            val harness = serve()
            dashboard.runStarted(RUN, listOf(status(1)))
            val seen = mutableListOf<ServerSentEvent>()

            withTimeout(10.seconds) {
                harness.client.sse("${harness.base}/api/stream") {
                    incoming.filter { it.event == "snapshot" }.take(2).collect {
                        seen += it
                        if (seen.size == 1) dashboard.message("a01 hello from the harness")
                    }
                }
            }

            val first = Json.parseToJsonElement(seen[0].data!!).jsonObject
            val second = Json.parseToJsonElement(seen[1].data!!).jsonObject
            second["version"]!!.jsonPrimitive.long shouldBe first["version"]!!.jsonPrimitive.long + 1
            second["timeline"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content shouldBe "a01 hello from the harness"
            seen[1].id shouldBe second["version"]!!.jsonPrimitive.content
        }

    @Test
    fun `the stream carries only the topics a screen asks for`() =
        runBlocking<Unit> {
            val harness = serve()
            dashboard.runStarted(RUN, listOf(status(1)))
            val events = mutableListOf<String>()

            withTimeout(10.seconds) {
                harness.client.sse("${harness.base}/api/stream?topics=run,orchestrator,jobs") {
                    incoming.filter { it.event != null }.take(3).collect { events += it.event!! }
                }
            }

            events.toSet() shouldBe setOf("run", "orchestrator", "jobs")
            Topic.parse(null) shouldBe setOf(Topic.BOARD)
            Topic.parse("nonsense, Board ,run") shouldBe setOf(Topic.BOARD, Topic.RUN)
        }

    @Test
    fun `an artifact of the run on the board is served with its type and cache headers`() =
        runBlocking<Unit> {
            val harness = serve()
            val shot = runWithScreenshot(harness)

            val response = harness.get("/artifacts/${shot.artifactId.value}")

            response.status.value shouldBe 200
            response.headers[HttpHeaders.ContentType] shouldBe "image/png"
            response.headers[HttpHeaders.CacheControl] shouldBe "private, max-age=31536000, immutable"
            response.headers[HttpHeaders.ETag] shouldBe "\"${shot.sha256}\""
            response.headers["Content-Security-Policy"].shouldNotBeNull() shouldContain "sandbox"
            response.bodyAsBytes().toList() shouldBe PNG.toList()
            val cached =
                harness.client.get("${harness.base}/artifacts/${shot.artifactId.value}") {
                    header(HttpHeaders.IfNoneMatch, "\"${shot.sha256}\"")
                }
            cached.status.value shouldBe 304
        }

    @Test
    fun `artifacts are served only for ids the dashboard recorded for the shown run`() =
        runBlocking<Unit> {
            val harness = serve()
            runWithScreenshot(harness)
            val foreign = harness.artifacts.write(OTHER_RUN, StepId("stp_x"), "a02", ArtifactType.SCREENSHOT, PNG)
            recorder.artifact(foreign)
            val unrecorded = harness.artifacts.write(RUN, StepId("stp_y"), "a02", ArtifactType.SCREENSHOT, PNG)

            harness.get("/artifacts/${foreign.artifactId.value}").status.value shouldBe 404
            harness.get("/artifacts/${unrecorded.artifactId.value}").status.value shouldBe 404
            harness.get("/artifacts/art_404").status.value shouldBe 404
            harness.raw("/artifacts/..%2F..%2Fsecret").first shouldBe 404
        }

    @Test
    fun `a captured page is served as plain text, never as HTML`() =
        runBlocking<Unit> {
            val harness = serve()
            dashboard.runStarted(RUN, listOf(status(2)))
            val dom = harness.artifacts.write(RUN, StepId("stp_dom"), "a02", ArtifactType.DOM, "<script>alert(1)</script>".toByteArray())
            recorder.artifact(dom)

            val response = harness.get("/artifacts/${dom.artifactId.value}")

            response.status.value shouldBe 200
            response.headers[HttpHeaders.ContentType].shouldNotBeNull() shouldStartWith "text/plain"
        }

    @Test
    fun `the report is served once it exists and only from inside its directory`() =
        runBlocking<Unit> {
            val report = dir.resolve("evidence/${RUN.value}/report").createDirectories()
            report.resolve("index.html").writeText("<h1>Hesabat</h1><img src=\"../a02/0001-screenshot.png\">")
            report.resolve("report.md").writeText("# Hesabat")
            dir.resolve("secret.txt").writeText("top secret")
            Files.createSymbolicLink(report.resolve("leak.txt"), dir.resolve("secret.txt"))
            var ready: Path? = null
            val harness = serve { ready }
            runWithScreenshot(harness)

            harness.get("/report/").status.value shouldBe 404
            ready = report

            harness.get("/report").status.value shouldBe 302
            val index = harness.get("/report/")
            index.status.value shouldBe 200
            index.bodyAsText() shouldContain "Hesabat"
            index.headers["Content-Security-Policy"].shouldNotBeNull() shouldContain "default-src 'none'"
            harness.get("/report/report.md").headers[HttpHeaders.ContentType].shouldNotBeNull() shouldStartWith "text/markdown"
            harness.get("/report/leak.txt").status.value shouldBe 404
            listOf(
                "/report/../../secret.txt",
                "/report/%2e%2e/%2e%2e/secret.txt",
                "/report/..%2f..%2fsecret.txt",
                "/report/.hidden",
            ).forEach {
                val (status, body) = harness.raw(it)
                status shouldNotBe 200
                body shouldNotContain "top secret"
            }
            harness.get("/a02/0001-screenshot.png").status.value shouldBe 200
            harness.get("/a02/9999-screenshot.png").status.value shouldBe 404
        }

    @Test
    fun `a request naming a foreign host or origin is refused`() =
        runBlocking<Unit> {
            val harness = serve()

            harness.raw("/api/snapshot", host = "evil.example:${harness.uri.port}").first shouldBe 403
            harness.raw("/api/snapshot", host = "localhost:${harness.uri.port}").first shouldBe 200
            harness.raw("/api/snapshot", host = "[::1]:${harness.uri.port}").first shouldBe 200
            harness.raw("/api/snapshot", extraHeaders = listOf("Origin: https://evil.example")).first shouldBe 403
            harness.raw("/api/snapshot", extraHeaders = listOf("Origin: http://127.0.0.1:${harness.uri.port}")).first shouldBe 200
        }

    @Test
    fun `the server binds to loopback only and refuses any other host`() {
        listOf("0.0.0.0", "192.168.1.10", "10.0.0.1", "::").forEach {
            shouldThrow<IllegalArgumentException> { DashboardServer(dashboard, TempDirArtifactStore(dir), host = it) }
        }
        shouldThrow<IllegalArgumentException> { DashboardServer(dashboard, TempDirArtifactStore(dir), port = 70_000) }
        val harness = serve()

        harness.uri.host shouldBe "127.0.0.1"
        val lan =
            NetworkInterface
                .networkInterfaces()
                .toList()
                .flatMap { it.inetAddresses().toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        if (lan != null) {
            val refused =
                try {
                    Socket().use { it.connect(InetSocketAddress(lan, harness.uri.port), 1_000) }
                    false
                } catch (_: ConnectException) {
                    true
                } catch (_: SocketTimeoutException) {
                    true
                }
            refused shouldBe true
        }
    }

    @Test
    fun `a taken port fails with a clear error and a server starts only once`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { taken ->
            val server = DashboardServer(dashboard, TempDirArtifactStore(dir), port = taken.localPort)
            val error = shouldThrow<IllegalStateException> { server.start() }
            error.message.shouldNotBeNull() shouldContain "${taken.localPort}"
        }
        val harness = serve()
        shouldThrow<IllegalStateException> { harness.server.start() }
        harness.server.stop()
        harness.server.stop()
        shouldThrow<IllegalStateException> { harness.server.uri }
    }

    @Test
    fun `the finished run links its report in the snapshot`() =
        runBlocking<Unit> {
            val report = dir.resolve("report").createDirectories()
            report.resolve("index.html").writeText("<h1>ok</h1>")
            val harness = serve()
            dashboard.runStarted(RUN, listOf(status(1)))

            dashboard.runFinished(RunSummary(RUN, RunOutcome.PASSED, 1, 0, 0, 0, report.toString(), 1_000))

            val json = Json.parseToJsonElement(harness.get("/api/snapshot").bodyAsText()).jsonObject
            json["report"]!!.jsonObject["ready"]!!.jsonPrimitive.content shouldBe "true"
            json["report"]!!.jsonObject["url"]!!.jsonPrimitive.content shouldBe "/report/"
            harness.get("/report/").bodyAsText() shouldBe "<h1>ok</h1>"
        }

    @Test
    fun `the orchestrator view is served as JSON`() =
        runBlocking<Unit> {
            val harness = serve()
            dashboard.runStarted(RUN, listOf(status(1)))

            val json = Json.parseToJsonElement(harness.get("/api/orchestrator").bodyAsText()).jsonObject

            json["plan"] shouldBe kotlinx.serialization.json.JsonNull
            json["tasks"]!!.jsonArray.shouldBeEmpty()
            json["run"]!!.jsonObject["runId"]!!.jsonPrimitive.content shouldBe RUN.value
        }

    private companion object {
        /** The smallest valid PNG: one transparent pixel. */
        val PNG: ByteArray =
            java.util.Base64
                .getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")
    }
}
