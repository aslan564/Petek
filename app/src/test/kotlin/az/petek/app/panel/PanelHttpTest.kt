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

import az.petek.app.testing.PanelHarness
import az.petek.app.testing.PanelHarness.Companion.tinyCampaign
import az.petek.app.testing.PanelWaits
import az.petek.app.testing.PanelWaits.ended
import az.petek.app.testing.PanelWaits.exploration
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.ExplorationStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/** Every screen's calls through the real server and wire format into the real backend (what the page does). */
class PanelHttpTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var panel: PanelHarness
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun close() {
        if (::panel.isInitialized) panel.close()
        http.close()
    }

    private fun url(path: String): URI = panel.panel.url.resolve(path)

    private suspend fun get(path: String): HttpResponse<String> =
        withContext(Dispatchers.IO) { http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString()) }

    private suspend fun post(
        path: String,
        json: String = "{}",
    ): HttpResponse<String> {
        val token = TOKEN.find(get("/").body())!!.groupValues[1]
        return withContext(Dispatchers.IO) {
            http.send(
                HttpRequest
                    .newBuilder(url(path))
                    .header("Content-Type", "application/json")
                    .header("X-Petek-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
    }

    private fun json(response: HttpResponse<String>): JsonElement = Json.parseToJsonElement(response.body())

    @Test
    fun `the page names the configured target and every screen's API reaches the backend`() =
        runBlocking<Unit> {
            panel = PanelHarness(dir, site = PanelWaits.site(), scenarios = mapOf("tiny.yaml" to tinyCampaign()))

            get("/").body() shouldContain "<meta name=\"petek-target\" content=\"http://127.0.0.1:9\">"
            json(get("/api/capacity?testers=6")).jsonObject["recommended"]!!.jsonPrimitive.int shouldBe
                panel.backend.capacity(6).recommended

            val instructions =
                """
                {"target":"http://127.0.0.1:9","instructions":"Giriş axını","testers":6,
                 "roles":{"admins":1,"managers":2,"employees":3},"departments":["IT","HR"],
                 "registration":{"invite":3,"companyCode":2},"budget":{"maxMinutes":5,"maxStepsPerAgent":20,"maxPages":10},
                 "allowWrites":false}
                """.trimIndent()
            post("/api/exploration", instructions).statusCode() shouldBe 202
            panel.exploration { it.status != ExplorationStatus.RUNNING && it.draftYaml != null }
            json(get("/api/exploration")).jsonObject["status"]!!.jsonPrimitive.content shouldBe "FINISHED"
            post("/api/exploration", instructions.replace("http://127.0.0.1:9", "https://kadrohr.com")).let {
                it.statusCode() shouldBe 400
                it.body() shouldContain "istehsal ünvanıdır"
            }

            val scenarios = json(get("/api/scenarios")).jsonArray
            val approved =
                scenarios
                    .single()
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            json(get("/api/scenarios/$approved/plan")).jsonObject["steps"]!!.jsonArray.size shouldBe 2
            post("/api/scenarios/generate").statusCode() shouldBe 201

            val started =
                post("/api/runs", """{"scenarioId":"$approved","testers":2,"headful":false,"target":"http://127.0.0.1:9"}""")
            started.statusCode() shouldBe 202
            val runId = RunId(json(started).jsonObject["runId"]!!.jsonPrimitive.content)
            panel.ended(runId)
            json(get("/api/orchestrator")).jsonObject["tasks"]!!.jsonArray.size shouldBe 2
            val history = json(get("/api/runs")).jsonArray.single().jsonObject
            history["scenarioId"]!!.jsonPrimitive.content shouldBe approved
            history["reportUrl"]!!.jsonPrimitive.content shouldBe "/runs/${runId.value}/report/"
            get("/runs/${runId.value}/report/").statusCode() shouldBe 200
            post("/api/runs", """{"scenarioId":"$approved","target":"kadrohr.com"}""").statusCode() shouldBe 400
            post("/api/runs/cancel").body() shouldBe """{"cancelled":false}"""
        }

    private companion object {
        val TOKEN = Regex("<meta name=\"petek-token\" content=\"([^\"]+)\">")
    }
}
