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

import az.petek.app.config.ConfigLoader
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.testing.DraftSiteDriver
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.HostResources
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.TestFlowView
import az.petek.dashboard.domain.TestStage
import az.petek.evidence.domain.RunResult
import az.petek.explorer.domain.ExplorationId
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import az.petek.faketarget.notes.FakeNotesServer
import az.petek.faketarget.notes.NotesBug
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Faza 10's ready-when (docs/PLAN.md): one panel knows two sites, its own (the contract fake target) and a second one by
 * its target profile (the notes site: no test API, a sign-in form), each with its own settings. The second is tested
 * from the same panel; its explorer signs in with the owner's account the profile names, the run's report shows the
 * strength of every finding's proof, and the panel's own site is not touched. Real Chromium, production wiring; the AI
 * is [DraftSiteDriver], which knows no site.
 */
@Tag("e2e")
class TwoSitesEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private val portal = FakeTargetServer(FakeTargetConfig()).start()
    private val notes =
        FakeNotesServer(setOf(NotesBug.DEAD_LINK)).start().apply { seedAccount("Sahib", OWNER, OWNER_PASSWORD) }
    private val driver = DraftSiteDriver()
    private lateinit var panel: WebPanel

    @AfterEach
    fun stop() {
        if (::panel.isInitialized) panel.close()
        notes.close()
        portal.close()
    }

    @Test
    fun `a second site is tested from the same panel, its explorer signing in with the owner's account`() =
        runBlocking<Unit> {
            val file = start()

            // The panel knows both sites, each with its own settings: the notes site has no test API of its own.
            val sites = panel.backend.sites().associateBy { it.name }
            sites.values.single { it.own }.testApi shouldBe true
            sites.getValue("notes").testApi shouldBe false
            sites.getValue("notes").accounts shouldBe 1

            panel.backend.startTest(
                PanelInstructions(
                    target = notes.baseUrl.toString(),
                    instructions = "",
                    testers = 3,
                    budget = PanelBudget(maxMinutes = 5, maxStepsPerAgent = 40, maxPages = 10),
                    allowWrites = false,
                ),
            )
            val ended = ended()

            withClue(ended.toString()) {
                ended.stage shouldBe TestStage.FINISHED
                // The site's dead link is found by the checks the draft wrote for the pages the explorer saw.
                ended.result shouldBe RunResult.FAILED
            }
            // The explorer signed in with the owner's account of the notes site's profile, as its role.
            val model =
                panel.container.explorations
                    .model(ExplorationId(ended.explorationId.shouldNotBeNull()))
                    .shouldNotBeNull()
            model.roles.map { it.name } shouldContain ROLE
            model.pages.filter { ROLE in it.reachableBy }.map { it.urlPattern } shouldContain "/"
            // The report names the strength of every finding's proof.
            val report =
                panel.backend
                    .reportDirectory(ended.runId.shouldNotBeNull())
                    .shouldNotBeNull()
                    .resolve("index.html")
            Files.readString(report) shouldContain "Ekran / şəbəkə sübutu"
            // The panel's own site was not touched, and the owner's password stayed in the configuration file.
            portal.store.companies.shouldBeEmpty()
            Files.readString(file) shouldContain "PETEK_ACC_NOTES_MEMBER"
        }

    /** The owner's directory: the portal as the panel's own site, the notes site as a target profile with an account. */
    private fun start(): Path {
        Files.createDirectories(dir.resolve("targets"))
        Files.writeString(
            dir.resolve("targets/notes.yaml"),
            """
            target:
              name: notes
              url: '${notes.baseUrl}'
              accounts:
                - {role: $ROLE, email: '$OWNER', password: '${'$'}{PETEK_ACC_NOTES_MEMBER}'}
            """.trimIndent() + "\n",
        )
        val file =
            dir.resolve(".env").also {
                Files.writeString(
                    it,
                    """
                    PETEK_TARGET=${portal.baseUrl}
                    PETEK_PRODUCTION_HOSTS=
                    PETEK_TEST_TOKEN=dev-token
                    PETEK_MAILPIT_URL=${portal.mailpitUrl}
                    PETEK_MAIL_DOMAIN=test.portal.example
                    PETEK_IDENTITY_SECRET=two-sites-e2e-secret
                    PETEK_LLM_PROVIDER=codex-cli
                    PETEK_LLM_CONCURRENCY=6
                    PETEK_BROWSER_HEADLESS=true
                    PETEK_EVIDENCE_DIR=evidence
                    PETEK_ACC_NOTES_MEMBER=$OWNER_PASSWORD
                    """.trimIndent() + "\n",
                )
            }
        val loader = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the configuration names its secret") })
        panel =
            WebPanel.start(
                config = loader.load(file),
                containers = { config, overrides -> AppContainer(config, overrides.copy(llm = driver.client)) },
                workingDirectory = dir,
                capacityAdvice = RecommendCapacityUseCase(HostResourceProbe { HostResources(64L shl 30, 32L shl 30, 32) }),
                port = 0,
                configurationFile = file,
                reloadConfig = { loader.load(file) },
            )
        return file
    }

    private suspend fun ended(): TestFlowView =
        withTimeout(10.minutes) {
            var view = panel.backend.testFlow()
            while (view == null || !view.stage.isFinal) {
                delay(POLL)
                view = panel.backend.testFlow()
            }
            view
        }

    private companion object {
        const val ROLE = "member"
        const val OWNER = "owner@notes.test"
        const val OWNER_PASSWORD = "owner-pass-123"
        val POLL = 200.milliseconds
    }
}
