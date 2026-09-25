package az.petek.dashboard.infrastructure

import az.petek.core.testing.FakeHarnessClock
import az.petek.core.testing.SequentialIdGenerator
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.demo.DemoPanelBackend
import az.petek.dashboard.testing.ServerHarness
import az.petek.orchestration.domain.RunOutcome
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Every panel endpoint against the demo backend, with the token rule for everything that changes something. */
class PanelRoutesTest {
    @TempDir
    lateinit var dir: Path

    private val clock = FakeHarnessClock()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runGate = CompletableDeferred<RunOutcome>()
    private var harness: ServerHarness? = null

    @AfterEach
    fun stop() {
        harness?.close()
        jobs.cancel()
    }

    private suspend fun serve(walkPause: suspend (Long) -> Unit = { awaitCancellation() }): ServerHarness {
        val root = dir.resolve("evidence")
        val artifacts =
            az.petek.dashboard.testing
                .TempDirArtifactStore(root)
        val backend = DemoPanelBackend(clock, SequentialIdGenerator(), artifacts, jobs, walkPause, root) { _, _ -> runGate.await() }
        backend.seed()
        return ServerHarness(LiveDashboard(clock), root, backend).also { harness = it }
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val instructions =
        """
        {"target":"https://staging.kadrohr.az","instructions":"Elan axınını yoxla","testers":30,
         "roles":{"admins":1,"managers":5,"employees":24},"departments":["IT","HR","Satış","Maliyyə","Əməliyyat"],
         "registration":{"invite":15,"companyCode":14},"budget":{"maxMinutes":30,"maxStepsPerAgent":40,"maxPages":40},
         "allowWrites":false}
        """.trimIndent()

    @Test
    fun `every mutating request without the page's token is refused`() =
        runBlocking<Unit> {
            val h = serve()
            val paths =
                listOf(
                    "/api/exploration",
                    "/api/exploration/cancel",
                    "/api/exploration/unknowns/u_1",
                    "/api/scenarios/generate",
                    "/api/scenarios/scn_core_v3/approve",
                    "/api/scenarios/scn_core_v2/freeze",
                    "/api/runs",
                    "/api/runs/cancel",
                    "/api/runs/run_demo_0921/triage",
                )

            paths.forEach {
                h.post(it).status.value shouldBe 403
                h.post(it, token = "wrong-token").status.value shouldBe 403
            }
            h.post("/api/runs/cancel", token = h.token()).status.value shouldBe 200
            h.get("/api/scenarios").status.value shouldBe 200
        }

    @Test
    fun `a request from another origin is refused even with a token`() =
        runBlocking<Unit> {
            val h = serve()
            val token = h.token()

            val response =
                h.client.post("${h.base}/api/runs/cancel") {
                    contentType(ContentType.Application.Json)
                    header("X-Petek-Token", token)
                    header("Origin", "https://evil.example")
                    setBody("{}")
                }

            response.status.value shouldBe 403
        }

    @Test
    fun `capacity advice is served for a sane tester count`() =
        runBlocking<Unit> {
            val h = serve()

            val advice = json(h.get("/api/capacity?testers=60").bodyAsText()).jsonObject

            advice["requested"]!!.jsonPrimitive.int shouldBe 60
            advice["recommended"]!!.jsonPrimitive.int shouldBe 41
            advice["exceeds"]!!.jsonPrimitive.boolean shouldBe true
            advice["limitingFactor"]!!.jsonPrimitive.content shouldBe "MEMORY"
            h.get("/api/capacity?testers=0").status.value shouldBe 400
            h.get("/api/capacity?testers=abc").status.value shouldBe 400
        }

    @Test
    fun `an exploration starts with valid instructions and invalid ones name their fields`() =
        runBlocking<Unit> {
            val h = serve()
            val token = h.token()

            val bad =
                h.post(
                    "/api/exploration",
                    instructions.replace("\"testers\":30", "\"testers\":31").replace("https://staging", "ftp://staging"),
                    token,
                )
            val problems = json(bad.bodyAsText()).jsonObject["problems"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content }
            bad.status.value shouldBe 400
            problems shouldBe listOf("target", "roles")
            h.post("/api/exploration", "not json", token).status.value shouldBe 400
            h.post("/api/exploration", "{\"target\":\"https://x.az\"}", token).status.value shouldBe 400

            val started = h.post("/api/exploration", instructions, token)
            val view = json(started.bodyAsText()).jsonObject

            started.status.value shouldBe 202
            view["status"]!!.jsonPrimitive.content shouldBe "RUNNING"
            view["target"]!!.jsonPrimitive.content shouldBe "https://staging.kadrohr.az"
            h.post("/api/exploration", instructions, token).status.value shouldBe 409
            json(h.post("/api/exploration/cancel", token = token).bodyAsText()).jsonObject["cancelled"]!!.jsonPrimitive.boolean shouldBe
                true
            json(h.get("/api/exploration").bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content shouldBe "CANCELLED"
            json(h.post("/api/exploration/cancel", token = token).bodyAsText()).jsonObject["cancelled"]!!.jsonPrimitive.boolean shouldBe
                false
        }

    @Test
    fun `the explorer's questions take answers that join the instructions`() =
        runBlocking<Unit> {
            val h = serve()
            val token = h.token()

            val answered = h.post("/api/exploration/unknowns/u_3", "{\"answer\":\"Bəli, yalnız test şirkətində.\"}", token)
            val view = json(answered.bodyAsText()).jsonObject

            answered.status.value shouldBe 200
            view["unknowns"]!!
                .jsonArray
                .first {
                    it.jsonObject["id"]!!.jsonPrimitive.content == "u_3"
                }.jsonObject["answer"]!!
                .jsonPrimitive.content shouldBe
                "Bəli, yalnız test şirkətində."
            view["instructions"]!!.jsonPrimitive.content shouldContain "Cavab: Bəli, yalnız test şirkətində."
            h.post("/api/exploration/unknowns/u_3", "{\"answer\":\"again\"}", token).status.value shouldBe 409
            h.post("/api/exploration/unknowns/u_9", "{\"answer\":\"x\"}", token).status.value shouldBe 404
            h.post("/api/exploration/unknowns/u_1", "{\"answer\":\"  \"}", token).status.value shouldBe 400
        }

    @Test
    fun `the current exploration, its artifacts and the model diff are readable`() =
        runBlocking<Unit> {
            val h = serve()

            val view = json(h.get("/api/exploration").bodyAsText()).jsonObject
            val shot = view["currentPage"]!!.jsonObject["screenshot"]!!.jsonPrimitive.content
            val diff = json(h.get("/api/exploration/diff").bodyAsText()).jsonObject

            view["status"]!!.jsonPrimitive.content shouldBe "RUNNING"
            view["visited"]!!.jsonArray shouldHaveSize 16
            view["model"]!!.jsonObject["pages"]!!.jsonArray.size shouldBe 8
            h.get("/artifacts/$shot").headers["Content-Type"] shouldBe "image/png"
            diff["toVersion"]!!.jsonPrimitive.int shouldBe 4
            diff["changes"]!!.jsonArray shouldHaveSize 4
        }

    @Test
    fun `scenarios list, show, diff, plan, approve and freeze`() =
        runBlocking<Unit> {
            val h = serve()
            val token = h.token()

            val list = json(h.get("/api/scenarios").bodyAsText()).jsonArray
            val v3 = json(h.get("/api/scenarios/scn_core_v3").bodyAsText()).jsonObject
            val diff = json(h.get("/api/scenarios/scn_core_v3/diff?from=scn_core_v2").bodyAsText()).jsonObject
            val plan = json(h.get("/api/scenarios/scn_core_v2/plan").bodyAsText()).jsonObject

            list shouldHaveSize 5
            v3["version"]!!.jsonObject["status"]!!.jsonPrimitive.content shouldBe "DRAFT"
            v3["yaml"]!!.jsonPrimitive.content shouldContain "Müraciət yarat"
            diff["added"]!!.jsonPrimitive.int shouldBe 2
            diff["removed"]!!.jsonPrimitive.int shouldBe 1
            diff["lines"]!!
                .jsonArray
                .first()
                .jsonObject["kind"]!!
                .jsonPrimitive.content shouldBe "HUNK"
            plan["steps"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } shouldContain "race"
            h.get("/api/scenarios/scn_nope").status.value shouldBe 404
            h.get("/api/scenarios/scn_core_v3/diff").status.value shouldBe 400
            h.get("/api/scenarios/scn_core_v3/diff?from=scn_nope").status.value shouldBe 404

            h.post("/api/scenarios/scn_core_v2/approve", token = token).status.value shouldBe 409
            json(
                h.post("/api/scenarios/scn_core_v3/approve", token = token).bodyAsText(),
            ).jsonObject["status"]!!.jsonPrimitive.content shouldBe
                "APPROVED"
            json(
                h.get("/api/scenarios/scn_core_v2").bodyAsText(),
            ).jsonObject["version"]!!.jsonObject["status"]!!.jsonPrimitive.content shouldBe
                "SUPERSEDED"
            json(
                h.post("/api/scenarios/scn_core_v3/freeze", token = token).bodyAsText(),
            ).jsonObject["status"]!!.jsonPrimitive.content shouldBe
                "FROZEN"
            h.post("/api/scenarios/scn_core_v3/freeze", token = token).status.value shouldBe 409
        }

    @Test
    fun `a scenario is generated from the exploration's draft`() =
        runBlocking<Unit> {
            val h = serve()

            val created = h.post("/api/scenarios/generate", token = h.token())
            val view = json(created.bodyAsText()).jsonObject

            created.status.value shouldBe 201
            view["version"]!!.jsonObject["source"]!!.jsonPrimitive.content shouldBe "EXPLORER"
            view["version"]!!.jsonObject["status"]!!.jsonPrimitive.content shouldBe "DRAFT"
            view["yaml"]!!.jsonPrimitive.content shouldStartWith "# Kəşfiyyatçının modelindən"
        }

    @Test
    fun `only one run at a time, and a run can be cancelled`() =
        runBlocking<Unit> {
            val h = serve()
            val token = h.token()

            h.post("/api/runs", "{\"scenarioId\":\"scn_core_v3\",\"testers\":30,\"headful\":false}", token).status.value shouldBe 409
            h.post("/api/runs", "{\"scenarioId\":null}", token).status.value shouldBe 400
            h.post("/api/runs", "{\"scenarioId\":\"scn_core_v2\",\"testers\":0}", token).status.value shouldBe 400
            val started = h.post("/api/runs", "{\"scenarioId\":\"scn_core_v2\",\"testers\":12,\"headful\":true}", token)
            val runId = json(started.bodyAsText()).jsonObject["runId"]!!.jsonPrimitive.content

            started.status.value shouldBe 202
            h.post("/api/runs", "{\"scenarioId\":\"scn_core_v2\"}", token).status.value shouldBe 409
            val running = json(h.get("/api/runs").bodyAsText()).jsonArray.first().jsonObject
            running["runId"]!!.jsonPrimitive.content shouldBe runId
            running["result"]!!.jsonPrimitive.content shouldBe "RUNNING"
            running["testers"]!!.jsonPrimitive.int shouldBe 12

            json(h.post("/api/runs/cancel", token = token).bodyAsText()).jsonObject["cancelled"]!!.jsonPrimitive.boolean shouldBe true
            json(h.get("/api/runs").bodyAsText())
                .jsonArray
                .first()
                .jsonObject["result"]!!
                .jsonPrimitive.content shouldBe "ABORTED"
            h.post("/api/runs", "{\"scenarioId\":\"scn_core_v2\"}", token).status.value shouldBe 202
        }

    @Test
    fun `runs, stability, triage and history reports are served`() =
        runBlocking<Unit> {
            val h = serve()
            val token = h.token()

            val runs = json(h.get("/api/runs").bodyAsText()).jsonArray
            val stability = json(h.get("/api/stability?group=rg-0924").bodyAsText()).jsonObject
            val triage = json(h.get("/api/runs/run_demo_0924c/triage").bodyAsText()).jsonObject

            runs shouldHaveSize 6
            runs
                .first()
                .jsonObject["reportUrl"]!!
                .jsonPrimitive.content shouldBe "/runs/run_demo_0924c/report/"
            stability["steps"]!!.jsonArray.count { it.jsonObject["flaky"]!!.jsonPrimitive.boolean } shouldBe 2
            triage["verdicts"]!!.jsonArray.map { it.jsonObject["category"]!!.jsonPrimitive.content } shouldBe
                listOf("SYSTEM_BUG", "MODEL_GAP", "SCENARIO_BUG")
            json(h.get("/api/runs/run_demo_0921/triage").bodyAsText()) shouldBe JsonNull
            json(h.post("/api/runs/run_demo_0921/triage", token = token).bodyAsText()).jsonObject["verdicts"]!!.jsonArray shouldHaveSize 1
            h.get("/api/stability?group=nope").bodyAsText() shouldBe "null"

            val report = h.get("/runs/run_demo_0924c/report/")
            report.status.value shouldBe 200
            val link = Regex("""src="\.\./([^"]+)"""").find(report.bodyAsText())!!.groupValues[1]
            h.get("/runs/run_demo_0924c/$link").headers["Content-Type"] shouldBe "image/png"
            h.get("/runs/run_demo_0914/report/").status.value shouldBe 404
            h.get("/runs/run_unknown/report/").status.value shouldBe 404
            h.raw("/runs/run_demo_0924c/report/%2e%2e/%2e%2e/x").first shouldBe 404
        }

    @Test
    fun `the exploration stream follows the walk to its end`() =
        runBlocking<Unit> {
            val h = serve(walkPause = { delay(1) })
            val token = h.token()
            h.post("/api/exploration", instructions, token).status.value shouldBe 202

            val finished =
                withTimeout(20.seconds) {
                    var last: String? = null
                    h.client.sse("${h.base}/api/stream?topics=exploration,jobs") {
                        last =
                            incoming
                                .filter { it.event == "exploration" && it.data!!.contains("\"status\":\"FINISHED\"") }
                                .first()
                                .data
                    }
                    json(last!!).jsonObject
                }

            finished["phases"]!!.jsonArray.map { it.jsonObject["state"]!!.jsonPrimitive.content } shouldBe listOf("DONE", "DONE", "SKIPPED")
            finished["draftYaml"]!!.jsonPrimitive.content shouldContain "kadrohr-explored"
        }

    @Test
    fun `a panel without a backend answers reads with empty data and actions with 503`() =
        runBlocking<Unit> {
            val h = ServerHarness(LiveDashboard(clock), dir.resolve("plain")).also { harness = it }
            val token = h.token()

            h.get("/api/scenarios").bodyAsText() shouldBe "[]"
            h.get("/api/runs").bodyAsText() shouldBe "[]"
            h.get("/api/exploration").bodyAsText() shouldBe "null"
            h.get("/api/capacity?testers=5").status.value shouldBe 503
            val refused = h.post("/api/scenarios/generate", token = token)
            refused.status.value shouldBe 503
            json(refused.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content shouldContain "qoşulmayıb"
        }
}
