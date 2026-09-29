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
import az.petek.app.config.EnvFile
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.testing.PanelLlm
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import az.petek.dashboard.domain.ExplorationPhase
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.PhaseState
import az.petek.dashboard.domain.RunPhase
import az.petek.dashboard.domain.ScenarioSource
import az.petek.dashboard.domain.ScenarioStatus
import az.petek.dashboard.domain.TestStage
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ScreenshotType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The owner's whole path through the REAL panel page in Chromium, against the in-process fake target with the
 * production object graph (real browsers for the explorer and the testers) and a scripted LLM: explore with the trial
 * touch allowed (a temporary test company gives the role sessions), send the draft to the scenarios, approve it, run it
 * with 6 testers, watch the agents and the orchestrator, open the report list and triage the run. Every screen is
 * photographed to `build/panel-screenshots/e2e-*.png` for a human look.
 */
@Tag("e2e")
class PanelEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private val target = FakeTargetServer(FakeTargetConfig(raceWindow = 5.seconds)).start()
    private val errors = mutableListOf<String>()
    private lateinit var panel: WebPanel
    private lateinit var playwright: Playwright
    private lateinit var context: BrowserContext

    @AfterEach
    fun stop() {
        if (::context.isInitialized) context.close()
        if (::playwright.isInitialized) playwright.close()
        if (::panel.isInitialized) panel.close()
        target.close()
    }

    @Test
    fun `the owner explores the demo site, approves the draft, runs it with 6 testers and triages the run from the page`() =
        runBlocking<Unit> {
            val llm = PanelLlm(failingSteps = emptySet())
            val file = dir.resolve("demo.env").also { Files.writeString(it, environment(target.baseUrl, target.mailpitUrl)) }
            val config = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the demo configuration names its secret") }).load(file)
            panel =
                WebPanel.start(
                    config = config,
                    containers = { cfg, overrides -> AppContainer(cfg, overrides.copy(llm = llm.client)) },
                    workingDirectory = dir,
                    capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                    port = 0,
                )
            val page = open()

            // Təlimat: the target is filled in from the configuration; allow the trial touch and explore.
            page.locator("input[aria-label='Hədəf sayt']").inputValue() shouldBe target.baseUrl.toString()
            page.locator("label.check input[type=checkbox]").check()
            page.locator(".tester-row input.num").fill("6")
            page.shoot("e2e-1-telimat")
            page.button("Kəşf et").click()
            page.waitForURL("**#/kesfiyyat")
            val explored =
                withTimeout(4.minutes) {
                    panel.backend.explorationUpdates.first { it.status != ExplorationStatus.RUNNING && it.draftYaml != null }
                }
            explored.status shouldBe ExplorationStatus.FINISHED
            explored.phases.single { it.phase == ExplorationPhase.ROLE_BASED }.state shouldBe PhaseState.DONE
            explored.phases.single { it.phase == ExplorationPhase.TRIAL_TOUCH }.state shouldBe PhaseState.DONE
            explored.model.pages
                .map { it.urlPattern }
                .shouldNotBeEmpty()
            // The demo site's own gate shows its companies: a code to join with and an admin who invites (Faza 25.1).
            val draft = explored.draftYaml.shouldNotBeNull()
            draft shouldContain "register_owner"
            // Oracle checks only where the trial touch saw the test API answer with what it created (Faza 25.2).
            draft shouldContain "/test/announcements/latest?by={self.email}"
            page.waitFor("() => document.body.innerText.includes('Bitdi')")
            page.shoot("e2e-2-kesfiyyat", full = true)

            // The explorer's first question is answered on the page; the answer joins the instructions.
            if (explored.unknowns.isNotEmpty()) {
                page.locator(".question textarea").first().fill("Bəli, hamı onu canlı görməlidir")
                page.button("Cavab ver").first().click()
                page.waitFor("() => document.querySelectorAll('.question .answered').length > 0")
                panel.backend
                    .exploration()
                    .shouldNotBeNull()
                    .instructions shouldContain "Cavab: Bəli, hamı onu canlı görməlidir"
            }

            // The draft goes to the scenarios and is approved there.
            page.button("Ssenarilərə göndər").first().click()
            page.waitForURL("**#/ssenariler**")
            page.waitFor("() => [...document.querySelectorAll('button')].some(b => b.textContent.includes('Təsdiqlə'))")
            page.shoot("e2e-3-ssenariler-layihe")
            page.button("Təsdiqlə").first().click()
            page.waitFor("() => [...document.querySelectorAll('button')].some(b => b.textContent.includes('Dondur'))")
            val approved = panel.backend.scenarios().single { it.source == ScenarioSource.EXPLORER }
            approved.status shouldBe ScenarioStatus.APPROVED
            page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Plan")).click()
            page.waitFor("() => document.querySelectorAll('.lane').length > 0")
            page.shoot("e2e-4-ssenariler-plan")

            // Run it from the instruction screen with 6 testers.
            page.navigate(panel.url.toString() + "#/telimat")
            page.waitFor(
                "() => [...document.querySelectorAll('select[aria-label=Ssenari] option')].some(o => o.value === '${approved.id}')",
            )
            page.locator("select[aria-label=Ssenari]").selectOption(approved.id)
            page.button("Run et").first().click()
            page.waitForURL("**#/agentler")
            page.waitFor("() => document.querySelectorAll('.agent').length >= 6")
            page.shoot("e2e-5-agentler")
            page.navigate(panel.url.toString() + "#/orkestrator")
            page.waitFor("() => document.querySelectorAll('.cell').length > 0")
            page.shoot("e2e-6-orkestrator")

            val runId =
                panel.dashboard
                    .snapshot()
                    .run.runId
                    .shouldNotBeNull()
            withTimeout(8.minutes) { panel.dashboard.updates.first { it.run.runId == runId && it.run.phase == RunPhase.FINISHED } }
            val orchestrator = panel.dashboard.orchestrator()
            orchestrator.plan
                .shouldNotBeNull()
                .steps.size shouldBeGreaterThan 0
            orchestrator.tasks.shouldNotBeEmpty()
            page.navigate(panel.url.toString() + "#/orkestrator")
            page.waitFor("() => document.querySelectorAll('.cell.ts-PASSED, .cell.ts-FAILED').length > 0")
            page.shoot("e2e-7-orkestrator-bitdi")
            page.navigate(panel.url.toString() + "#/agentler")
            page.waitFor("() => document.querySelectorAll('.agent').length >= 6")
            page.shoot("e2e-8-agentler-bitdi")

            // Reports list the run with its report; triage it from the scenario screen.
            page.navigate(panel.url.toString() + "#/hesabatlar")
            page.waitFor("() => document.querySelectorAll('tr[data-run]').length > 0")
            page.shoot("e2e-9-hesabatlar")
            val history = panel.backend.runs().first { it.runId == runId }
            history.reportAvailable shouldBe true
            history.testers shouldBe 6
            history.scenarioId shouldBe approved.id
            page.navigate(panel.url.toString() + "#/ssenariler?run=" + runId.value)
            page.waitFor(
                "() => [...document.querySelectorAll('button')].some(b => b.textContent.includes('Triaj et')) || document.body.innerText.includes('sürpriz yoxdur')",
            )
            if (page.button("Triaj et").count() > 0) {
                page.button("Triaj et").first().click()
                page.waitFor(
                    "() => document.querySelectorAll('.verdict').length > 0 || document.body.innerText.includes('sürpriz yoxdur')",
                    TRIAGE_MILLIS,
                )
            }
            page.shoot("e2e-10-triaj")
            val triage = panel.backend.triage(runId).shouldNotBeNull()
            triage.runId shouldBe runId
            if (triage.verdicts.any { it.evidence.isNotEmpty() }) {
                // The evidence a verdict cites opens from the page (a screenshot of the run).
                val href =
                    page
                        .locator(".verdict .proofs a")
                        .first()
                        .getAttribute("href")
                        .shouldNotBeNull()
                page.request().get(panel.url.resolve(href).toString()).status() shouldBe 200
            }

            // The page itself never failed.
            errors.shouldBeEmpty()
            page.title() shouldStartWith "Ssenarilər"
            panel.url.toString() shouldContain "127.0.0.1"
        }

    @Test
    fun `the owner tests the demo site with one button, which explores, drafts from what was found, approves and runs it`() =
        runBlocking<Unit> {
            val llm = PanelLlm(failingSteps = emptySet())
            val file = dir.resolve("demo.env").also { Files.writeString(it, environment(target.baseUrl, target.mailpitUrl)) }
            val config = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the demo configuration names its secret") }).load(file)
            panel =
                WebPanel.start(
                    config = config,
                    containers = { cfg, overrides -> AppContainer(cfg, overrides.copy(llm = llm.client)) },
                    workingDirectory = dir,
                    capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                    port = 0,
                )
            val page = open()

            // "Test et" with the trial touch and 6 testers; the team is left to what the explorer sees (automatic split).
            page.locator("label.check input[type=checkbox]").check()
            page.locator(".tester-row input.num").fill("6")
            page.button("Test et").click()
            page.waitForURL("**#/kesfiyyat")
            // The run's start opens the live board by itself.
            page.waitForURL("**#/agentler", Page.WaitForURLOptions().setTimeout(TEST_MILLIS))
            val ended =
                withTimeout(12.minutes) {
                    var view = panel.backend.testFlow()
                    while (view == null || !view.stage.isFinal) {
                        delay(POLL_MILLIS)
                        view = panel.backend.testFlow()
                    }
                    view
                }
            page.shoot("e2e-11-test-et")

            ended.stage shouldBe TestStage.FINISHED
            ended.result.shouldNotBeNull()
            val explored = panel.backend.exploration().shouldNotBeNull()
            explored.status shouldBe ExplorationStatus.FINISHED
            ended.explorationId shouldBe explored.id
            val version = panel.backend.scenarios().single { it.id == ended.scenarioId }
            version.source shouldBe ScenarioSource.EXPLORER
            version.status shouldBe ScenarioStatus.APPROVED
            // Drafted only from what the explorer found on the site: its own way into a company (Faza 25.1).
            panel.backend
                .scenario(version.id)
                .shouldNotBeNull()
                .yaml shouldContain "register_owner"
            val run = panel.backend.runs().single { it.runId == ended.runId }
            run.scenarioId shouldBe version.id
            run.testers shouldBe 6
            run.reportAvailable shouldBe true
            errors.shouldBeEmpty()
        }

    @Test
    fun `a fresh browser opens on the setup screen, which checks the site in the page and sets the instructions' tester count`() =
        runBlocking<Unit> {
            val llm = PanelLlm(failingSteps = emptySet())
            val file = dir.resolve("demo.env").also { Files.writeString(it, environment(target.baseUrl, target.mailpitUrl)) }
            val loader = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the demo configuration names its secret") })
            panel =
                WebPanel.start(
                    config = loader.load(file),
                    containers = { cfg, overrides -> AppContainer(cfg, overrides.copy(llm = llm.client)) },
                    workingDirectory = dir,
                    capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                    port = 0,
                    configurationFile = file,
                    reloadConfig = { loader.load(file) },
                )
            val page = open(hash = "", ready = "() => location.hash === '#/qurasdirma'")

            // The site answers and, being local, needs no proof; the AI answers the health check.
            page.waitFor("() => document.body.innerText.includes('Sayt cavab verir')")
            page.waitFor("() => document.body.innerText.includes('Təsdiq lazım deyil')")
            page.locator("section.screen[aria-label='Quraşdırma'] .tester-row input.num").fill("7")
            page.button("Sına").click()
            page.waitFor("() => document.body.innerText.includes('AI cavab verdi')")

            // Another AI is chosen here: kept in the configuration file, its key only there, shown without a restart.
            val setup = page.locator("section.screen[aria-label='Quraşdırma']")
            page.button("Dəyiş").click()
            setup.locator("select[aria-label='AI']").selectOption("openai-compat")
            setup.locator("input[aria-label='Model']").fill("e2e-model")
            setup.locator("input[aria-label='Endpoint']").fill("http://127.0.0.1:9/v1")
            setup.locator("input[aria-label='API açarı']").fill("sk-e2e-key-0123456789")
            page.shoot("e2e-0-ai-secimi")
            page.button("Yadda saxla").click()
            page.waitFor("() => document.body.innerText.includes('openai-compat · e2e-model')")
            page.content() shouldNotContain "sk-e2e-key"
            EnvFile.load(file)["PETEK_LLM_API_KEY"] shouldBe "sk-e2e-key-0123456789"
            page.shoot("e2e-0-qurasdirma")

            // Done: the instructions take over, with the tester count chosen here.
            page.button("Hazırdır: saytı test et").click()
            page.waitForURL("**#/telimat")
            page.locator("section.screen[aria-label='Təlimat'] .tester-row input.num").inputValue() shouldBe "7"

            // Another site with its own settings: added here, known at once without a restart, chosen as the target.
            val instructions = page.locator("section.screen[aria-label='Təlimat']")
            instructions.locator("input[aria-label='Saytın adı']").fill("notes")
            instructions.locator("input[aria-label='Saytın ünvanı']").fill("https://notes.test")
            instructions.locator("select[aria-label='Saytın poçtu']").selectOption("manual")
            page.button("Sayt əlavə et").click()
            val row = instructions.locator("[data-site='notes']")
            row.waitFor()
            row.innerText() shouldContain "poçt: manual"
            row.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Seç").setExact(true)).click()
            instructions.locator("input[aria-label='Hədəf sayt']").inputValue() shouldStartWith "https://notes.test"
            Files.readString(dir.resolve("targets/notes.yaml")) shouldContain "source: manual"
            page.shoot("e2e-0-saytlar")
            errors.shouldBeEmpty()
        }

    private fun open(
        hash: String = "#/telimat",
        ready: String = "() => document.querySelector(\"input[aria-label='Hədəf sayt']\") !== null",
    ): Page {
        playwright = Playwright.create()
        val browser = playwright.chromium().launch()
        context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setViewportSize(WIDTH, HEIGHT)
                    .setLocale("az-AZ")
                    .setTimezoneId("Asia/Baku"),
            )
        val page = context.newPage()
        page.onConsoleMessage { message ->
            val expected = message.text().startsWith("Failed to load resource")
            if (message.type() == "error" && !expected) errors += message.text()
        }
        page.onPageError { errors += it }
        page.setDefaultTimeout(WAIT_MILLIS)
        page.navigate(panel.url.toString() + hash)
        page.waitFor(ready)
        return page
    }

    private fun Page.button(name: String) = getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))

    private fun Page.waitFor(
        script: String,
        timeout: Double = WAIT_MILLIS,
    ) {
        waitForFunction(script, null, Page.WaitForFunctionOptions().setTimeout(timeout))
    }

    /** A viewport shot by default: the sidebar and header are sticky and repeat in a full-page picture. */
    private fun Page.shoot(
        name: String,
        full: Boolean = false,
    ) {
        waitForTimeout(SETTLE_MILLIS)
        Files.createDirectories(SHOTS)
        screenshot(
            Page
                .ScreenshotOptions()
                .setPath(SHOTS.resolve("$name.png"))
                .setType(ScreenshotType.PNG)
                .setFullPage(full),
        )
    }

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 1500
        const val WAIT_MILLIS = 60_000.0
        const val TRIAGE_MILLIS = 120_000.0
        const val TEST_MILLIS = 360_000.0
        const val POLL_MILLIS = 500L
        const val SETTLE_MILLIS = 600.0
        val SHOTS: Path = Path.of("build", "panel-screenshots")

        /** Same values as `.env.fake-target`, pointing at this test's own fake site; its token only unlocks that site. */
        fun environment(
            target: URI,
            mailpit: URI,
        ): String =
            """
            PETEK_TARGET=$target
            PETEK_PRODUCTION_HOSTS=
            PETEK_ALLOW_PRODUCTION=false
            PETEK_TEST_TOKEN=dev-token
            PETEK_MAILPIT_URL=$mailpit
            PETEK_MAIL_DOMAIN=test.portal.example
            PETEK_IDENTITY_SECRET=local-demo-secret
            PETEK_LLM_PROVIDER=codex-cli
            PETEK_LLM_CONCURRENCY=6
            PETEK_BROWSER_HEADLESS=true
            PETEK_EVIDENCE_DIR=evidence
            PETEK_DB=evidence/petek.db
            """.trimIndent() + "\n"
    }
}
