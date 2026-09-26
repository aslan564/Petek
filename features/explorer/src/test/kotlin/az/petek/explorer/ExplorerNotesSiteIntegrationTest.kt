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
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.DefaultTemplateRenderer
import az.petek.campaign.domain.Tenant
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.core.ids.UuidV7IdGenerator
import az.petek.core.model.RegistrationMode
import az.petek.core.security.TargetPolicy
import az.petek.core.sqlite.SqliteDatabase
import az.petek.core.time.SystemHarnessClock
import az.petek.evidence.infrastructure.FileSystemArtifactStore
import az.petek.explorer.application.ExploreSiteUseCase
import az.petek.explorer.application.GenerateScenarioUseCase
import az.petek.explorer.application.ScenarioRequest
import az.petek.explorer.domain.ExplorationBudget
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.GateMaps
import az.petek.explorer.domain.OtpKind
import az.petek.explorer.domain.SiteKind
import az.petek.explorer.domain.SiteKinds
import az.petek.explorer.infrastructure.SqliteExplorationRepository
import az.petek.faketarget.notes.FakeNotesServer
import az.petek.llm.testing.ScriptedLlmClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * Faza 17-18 in a real Chromium against the second fake site (a notes app without companies): the anonymous pass finds
 * the gate, the explorer names the site's kind, and the draft for a site without companies carries the learned gate
 * so testers pass it by code with the default flows. Nothing is written to the site.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExplorerNotesSiteIntegrationTest {
    private val clock = SystemHarnessClock()
    private val site = FakeNotesServer().start()
    private val engine = PlaywrightBrowserEngine(clock)

    @TempDir
    lateinit var dir: Path

    private val button = Regex("""\[(\d+)] button "([^"]*)" \(testid=([a-z-]+)\)""")

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
        site.close()
    }

    @Test
    fun `the explorer maps the notes site's gate and kind and the draft passes the gate by code`() =
        runBlocking<Unit> {
            val sessions = engine.start(BrowserEngineConfig())
            SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
                val repository = SqliteExplorationRepository(db)
                val explorer =
                    ExploreSiteUseCase(
                        sessions,
                        llm,
                        FileSystemArtifactStore(dir.resolve("evidence")),
                        repository,
                        clock,
                        UuidV7IdGenerator(),
                        TargetPolicy(emptySet(), allowProduction = false),
                    )

                val result =
                    explorer.execute(
                        ExplorationRequest(
                            target = site.baseUrl,
                            instructions = "Qeydiyyat və giriş",
                            budget = ExplorationBudget(maxPages = 8, maxMinutes = 2),
                            phases = setOf(ExplorationPhase.ANONYMOUS),
                        ),
                    )

                result.record.status shouldBe ExplorationStatus.COMPLETED
                val gate = GateMaps.of(result.model)
                val register = gate.register.shouldNotBeNull()
                register.path shouldBe "/register"
                register.selectors shouldBe
                    mapOf(
                        "register.name" to "[data-testid=\"register-name\"]",
                        "register.email" to "[data-testid=\"register-email\"]",
                        "register.password" to "[data-testid=\"register-password\"]",
                        "register.submit" to "[data-testid=\"register-submit\"]",
                    )
                register.unmapped.shouldBeEmpty()
                gate.login.shouldNotBeNull().path shouldBe "/login"
                gate.captcha shouldBe false
                gate.otp shouldBe OtpKind.NOT_SEEN
                gate.blockers.shouldBeEmpty()
                SiteKinds.of(result.model).kind shouldBe SiteKind.SIGN_IN_SYSTEM
                site.accounts shouldBe 0

                val draft =
                    GenerateScenarioUseCase(
                        DefaultCampaignValidator(DefaultTemplateRenderer()),
                        DefaultTemplateRenderer(),
                        setOf("register_and_login", "login", "verify_identity", "site_health", "direct_url"),
                        repository,
                        clock,
                        UuidV7IdGenerator(),
                    ).compose(result.model, ScenarioRequest(result.record.id, tenant = Tenant.NONE))

                draft.yaml shouldContain "site kind: sign_in_system"
                val file = dir.resolve("draft.yaml").also { Files.writeString(it, draft.yaml) }
                val loaded = YamlCampaignSource().load(file)
                loaded.settings.tenant shouldBe Tenant.NONE
                loaded.target.paths["register"] shouldBe "/register"
                loaded.target.selectors["register.email"] shouldBe "[data-testid=\"register-email\"]"
                loaded.settings.registration.count(RegistrationMode.GUEST) shouldBe loaded.settings.testers
            }
        }
}
