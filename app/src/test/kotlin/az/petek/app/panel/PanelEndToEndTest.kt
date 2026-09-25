/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

import az.petek.app.config.ConfigLoader
import az.petek.app.config.IdentitySecretSource
import az.petek.app.demo.DemoTarget
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
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
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
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The owner's whole path through the REAL panel page in Chromium, against the in-process fake KadroHR with the
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
            val file = dir.resolve("demo.env").also { Files.writeString(it, DemoTarget.environment(target.baseUrl, target.mailpitUrl)) }
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
            page.locator("input[type=url]").inputValue() shouldBe target.baseUrl.toString()
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

    private fun open(): Page {
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
        page.navigate(panel.url.toString() + "#/telimat")
        page.waitFor("() => document.querySelector('input[type=url]') !== null")
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
        const val SETTLE_MILLIS = 600.0
        val SHOTS: Path = Path.of("build", "panel-screenshots")
    }
}
