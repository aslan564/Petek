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

package az.petek.explorer

import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.infrastructure.PlaywrightBrowserEngine
import az.petek.core.ids.UuidV7IdGenerator
import az.petek.core.security.TargetPolicy
import az.petek.core.sqlite.SqliteDatabase
import az.petek.core.time.SystemHarnessClock
import az.petek.evidence.infrastructure.FileSystemArtifactStore
import az.petek.explorer.application.ExploreSiteUseCase
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ExplorationBudget
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.infrastructure.SqliteExplorationRepository
import az.petek.faketarget.FakeTargetServer
import az.petek.llm.testing.ScriptedLlmClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The explorer against the fake target (testing/fake-target) in a real Chromium, with a scripted LLM: the anonymous
 * pass must find the sign-in and the two sign-up pages with their forms, and change nothing on the site.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExplorerFakeTargetIntegrationTest {
    private val clock = SystemHarnessClock()
    private val server = FakeTargetServer().start()
    private val engine = PlaywrightBrowserEngine(clock)

    @TempDir
    lateinit var dir: Path

    private val button = Regex("""\[(\d+)] button "([^"]*)" \(testid=([a-z-]+)\)""")

    /** Names each submit button as an action, as a model would; the purpose is the page pattern. */
    private val llm =
        ScriptedLlmClient { request ->
            val prompt = request.messages.single().content
            buildJsonObject {
                put("purpose", "Page " + Regex("URL: (\\S+)").find(prompt)?.groupValues?.get(1))
                putJsonArray("actions") {
                    button.findAll(prompt).filter { it.groupValues[3].endsWith("-submit") }.forEach { match ->
                        addJsonObject {
                            put("ref", match.groupValues[1].toInt())
                            put("name", match.groupValues[2])
                            put("kind", if (match.groupValues[3].startsWith("login")) "LOGIN" else "REGISTER")
                        }
                    }
                }
                putJsonArray("unknowns") {}
            }
        }

    @AfterAll
    fun stop() {
        runBlocking { engine.stop() }
        server.close()
    }

    @Test
    fun `the anonymous pass learns the fake target sign-in and sign-up pages and their forms without changing anything`() =
        runBlocking<Unit> {
            val sessions = engine.start(BrowserEngineConfig())
            SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
                val repository = SqliteExplorationRepository(db)
                val artifacts = FileSystemArtifactStore(dir.resolve("evidence"))
                val explorer =
                    ExploreSiteUseCase(
                        sessions,
                        llm,
                        artifacts,
                        repository,
                        clock,
                        UuidV7IdGenerator(),
                        TargetPolicy(emptySet(), allowProduction = false),
                    )

                val result =
                    explorer.execute(
                        ExplorationRequest(
                            target = server.baseUrl,
                            instructions = "Qeydiyyat və giriş axınları",
                            budget = ExplorationBudget(maxPages = 12, maxMinutes = 3),
                            phases = setOf(ExplorationPhase.ANONYMOUS),
                        ),
                    )

                result.record.status shouldBe ExplorationStatus.COMPLETED
                val model = result.model
                model.pages.map { it.urlPattern } shouldContainAll listOf("/login", "/register", "/join")

                val login = model.pageByPattern("/login")!!.forms.single { it.kind == ActionKind.LOGIN }
                login.fields.map { it.name } shouldContainExactly listOf("email", "password")
                login.submitSelector shouldBe "[data-testid=\"login-submit\"]"
                val register = model.pageByPattern("/register")!!.forms.single { it.kind == ActionKind.REGISTER }
                register.fields.map { it.name } shouldContainExactly listOf("name", "email", "phone", "password", "company")
                register.fields.map { it.testId } shouldContainExactly
                    listOf("register-name", "register-email", "register-phone", "register-password", "register-company")
                val join = model.pageByPattern("/join")!!.forms.single { it.kind == ActionKind.REGISTER }
                join.fields.map { it.name } shouldContainExactly listOf("code", "name", "email", "phone", "password", "department")
                join.fields.last().type shouldBe "select"
                join.fields.map { it.label } shouldContainExactly
                    listOf("Şirkət kodu", "Ad Soyad", "E-poçt", "Telefon", "Parol", "Departament")

                model.actions.map { it.id } shouldContainAll listOf("login-submit", "register-submit", "join-submit")
                model.action("login-submit")!!.kind shouldBe ActionKind.LOGIN
                model.roles.single().deniedPatterns shouldBe setOf("/")
                // The live notification stream (SSE) belongs to logged-in pages; an anonymous visitor never opens it.
                model.realtime.shouldBeEmpty()
                result.findings.filter { it.kind == FindingKind.BROKEN_LINK || it.kind == FindingKind.HTTP_ERROR }.shouldBeEmpty()

                server.store.users.shouldBeEmpty()
                server.store.companies.shouldBeEmpty()

                repository.model(result.record.id) shouldBe model
                val events = repository.events(result.record.id)
                events.last().shouldBeInstanceOf<ExplorationEvent.Finished>()
                val screenshot =
                    events
                        .filterIsInstance<ExplorationEvent.PageVisited>()
                        .first()
                        .screenshotArtifactId
                        .shouldNotBeNull()
                val file = artifacts.resolve(repository.artifact(screenshot).shouldNotBeNull())
                Files.readAllBytes(file).take(4) shouldBe listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
                // Prompts show page patterns, never addresses (which may carry tokens).
                llm.requests.forEach { it.messages.single().content shouldNotContain server.baseUrl.toString() }
            }
        }
}
