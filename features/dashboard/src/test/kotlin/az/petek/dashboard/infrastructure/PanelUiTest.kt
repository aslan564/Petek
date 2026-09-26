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

import az.petek.core.testing.FakeHarnessClock
import az.petek.core.testing.SequentialIdGenerator
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.demo.DemoPanelBackend
import az.petek.dashboard.demo.DemoRun
import az.petek.dashboard.testing.TempDirArtifactStore
import az.petek.orchestration.domain.RunOutcome
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.ReducedMotion
import com.microsoft.playwright.options.ScreenshotType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * The panel in a real Chromium: a simulated 40-agent run on the board, the demo backend behind every other screen.
 * Checks that each screen renders and reacts, that nothing logs an error (a CSP violation would), and writes screenshots
 * of every screen — desktop 1440 px and mobile 390 px, light and dark — to `build/panel-screenshots/` for a human look.
 */
class PanelUiTest {
    @TempDir
    lateinit var dir: Path

    private val clock = FakeHarnessClock()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: DashboardServer
    private lateinit var base: String
    private val contexts = mutableListOf<BrowserContext>()
    private val errors = mutableListOf<String>()

    @AfterEach
    fun stop() {
        contexts.forEach { it.close() }
        if (::server.isInitialized) server.stop()
        jobs.cancel()
        errors.shouldBeEmpty()
    }

    /** Serves the panel with [agents] testers in the middle of the run and the seeded demo backend. */
    private fun serve(agents: Int = 40) {
        val root = dir.resolve("evidence")
        val artifacts = TempDirArtifactStore(root)
        val dashboard = LiveDashboard(clock)
        val ids = SequentialIdGenerator()
        runBlocking {
            DemoRun(agents, dashboard, artifacts, clock, ids, { clock.advance(it.milliseconds) }).populate()
            clock.advance(STEP_GAP)
            val backend = DemoPanelBackend(clock, ids, artifacts, jobs, { awaitCancellation() }, root) { _, _ -> RunOutcome.PASSED }
            backend.seed()
            server = DashboardServer(dashboard, artifacts, port = 0, backend = backend)
        }
        base = server.start().toString().removeSuffix("/")
    }

    private fun open(
        route: String,
        width: Int = DESKTOP_WIDTH,
        height: Int = DESKTOP_HEIGHT,
        dark: Boolean = false,
    ): Page {
        val mobile = width < MOBILE_BELOW
        val context =
            browser.newContext(
                Browser
                    .NewContextOptions()
                    .setViewportSize(width, height)
                    .setDeviceScaleFactor(if (mobile) 2.0 else 1.0)
                    .setIsMobile(mobile)
                    .setHasTouch(mobile)
                    .setColorScheme(if (dark) ColorScheme.DARK else ColorScheme.LIGHT)
                    .setReducedMotion(ReducedMotion.REDUCE)
                    .setTimezoneId("Asia/Baku")
                    .setLocale("az-AZ"),
            )
        contexts += context
        val page = context.newPage()
        // Expected 4xx answers of negative tests show up as "Failed to load resource"; script errors and CSP violations must not.
        page.onConsoleMessage { message ->
            val expected = message.text().startsWith("Failed to load resource")
            if (message.type() == "error" && !expected) errors += "$route: ${message.text()}"
        }
        page.onPageError { errors += "$route: $it" }
        page.navigate("$base/#/$route")
        return page
    }

    private fun Page.waitFor(script: String) {
        waitForFunction(script, null, Page.WaitForFunctionOptions().setTimeout(WAIT_MILLIS))
    }

    /** Waits until every image in the viewport has loaded, so screenshots never catch a half-drawn thumbnail. */
    private fun Page.settle() {
        waitFor(
            "() => [...document.images].filter(i => { const r = i.getBoundingClientRect(); return r.bottom > 0 && r.top < innerHeight && r.width > 0; }).every(i => i.complete)",
        )
        waitForTimeout(SETTLE_MILLIS)
    }

    /** Scrolls through the page once, so every lazily loaded thumbnail is in a full-page screenshot. */
    private fun Page.loadEveryThumbnail() {
        evaluate(
            "async () => { for (let y = 0; y < document.body.scrollHeight; y += 500) { scrollTo(0, y); await new Promise(r => setTimeout(r, 40)); } scrollTo(0, 0); }",
        )
        waitFor("() => [...document.querySelectorAll('.agent[data-screenshot] .thumb')].every(t => t.classList.contains('has-shot'))")
    }

    private fun Page.shoot(
        name: String,
        full: Boolean = true,
    ): Path {
        val file = SHOTS.resolve("$name.png")
        Files.createDirectories(SHOTS)
        val options =
            Page
                .ScreenshotOptions()
                .setPath(file)
                .setType(ScreenshotType.PNG)
                .setFullPage(full)
        screenshot(options)
        return file
    }

    private fun Page.count(selector: String): Int = locator(selector).count()

    private fun Page.text(selector: String): String =
        locator(selector)
            .first()
            .textContent()
            .orEmpty()
            .trim()

    @Test
    fun `the agent board renders a 40-agent run with filters, search and the detail panel`() {
        serve()
        val page = open("agentler")

        page.waitFor("() => document.querySelectorAll('.agent').length === 40")
        page.settle()
        page.text("#screenTitle") shouldBe "Agentlər"
        page.count(".agent-grid.compact") shouldBe 0
        page.text(".tile-agents .tile-big") shouldBe "40"
        val failed = page.text(".chip[data-key=FAILED] .n").toInt()
        failed shouldBe 1
        page.locator(".chip[data-key=FAILED]").click()
        page.count(".agent:not([hidden])") shouldBe failed
        page.locator(".chip[data-key=FAILED]").click()
        page.locator(".search input").fill("a05")
        page.count(".agent:not([hidden])") shouldBe 1
        page.locator(".search input").fill("")
        page.count(".agent:not([hidden])") shouldBe 40
        page.count(".timeline .entry") shouldBeGreaterThan 20
        page.locator(".tab", Page.LocatorOptions().setHasText("Tapıntılar")).click()
        page.count(".finding") shouldBe 3
        page.locator(".tab", Page.LocatorOptions().setHasText("Axın")).click()

        page.locator(".agent[data-agent=a02]").click()
        page.waitFor("() => document.querySelectorAll('.drawer .timeline .entry').length > 0")
        page.text(".drawer .drawer-title h2").length shouldBeGreaterThan 3
        page.settle()
        page.shoot("agentler-drawer-desktop-light", full = false)
        page.keyboard().press("Escape")
        page.count(".drawer:not([hidden])") shouldBe 0
    }

    @Test
    fun `more than sixty agents switch to compact cards and load only the thumbnails in view`() {
        serve(agents = 120)
        val page = open("agentler")

        page.waitFor("() => document.querySelectorAll('.agent').length === 120")
        page.settle()
        page.count(".agent-grid.compact") shouldBe 1
        val loaded = page.count(".thumb.has-shot")
        loaded shouldBeGreaterThan 0
        loaded shouldBeLessThan 120
        page.shoot("agentler-compact-desktop-light", full = false)
        page.locator(".agent[data-agent=a120]").scrollIntoViewIfNeeded()
        page.waitFor("() => document.querySelector('.agent[data-agent=a120] .thumb').classList.contains('has-shot')")
        page.count(".thumb.has-shot") shouldBeGreaterThan loaded
        page.locator(".segmented button[aria-label='Böyük kartlar']").click()
        page.count(".agent-grid.compact") shouldBe 0
    }

    @Test
    fun `every screen is reachable from the sidebar and shows its data`() {
        serve()
        val page = open("telimat")
        page.waitFor("() => document.querySelector('.capacity:not(.unknown)') !== null")
        page.text(".capacity") shouldContain "41 tester"

        page.locator(".nav-item[data-screen=kesfiyyat]").click()
        page.waitFor("() => document.querySelectorAll('.visit').length === 16")
        page.count(".tree details") shouldBe 8
        page.count(".question textarea") shouldBe 2

        page.locator(".nav-item[data-screen=ssenariler]").click()
        page.waitFor("() => document.querySelectorAll('.scn-version').length === 5")
        page.waitFor("() => document.querySelectorAll('.verdict').length === 3")

        page.locator(".nav-item[data-screen=orkestrator]").click()
        page.waitFor("() => document.querySelectorAll('.lane').length === 9")
        page.count(".lanes .cell") shouldBe 1 + 1 + 39 + 1 + 34 + 1 + 1 + 2 + 2

        page.locator(".nav-item[data-screen=hesabatlar]").click()
        // The run list and the stability card load separately; wait for both before counting (as the reports test does).
        page.waitFor(
            "() => document.querySelectorAll('.screen:not([hidden]) tbody tr').length === 6 && document.querySelectorAll('.stab-card').length === 1",
        )
        page.count(".stab-card") shouldBe 1

        page.locator(".nav-item[data-screen=agentler]").click()
        page.waitFor("() => document.querySelectorAll('.agent').length === 40")
        page.text(".nav-item[data-screen=agentler] .nav-badge").toInt() shouldBeGreaterThan 0
    }

    @Test
    fun `the instruction form warns above the capacity, shows problems and starts an exploration`() {
        serve()
        val page = open("telimat")
        page.waitFor("() => document.querySelector('.capacity:not(.unknown)') !== null")

        page.locator("[data-field=testers] input[type=number]").fill("60")
        page.waitFor("() => document.querySelector('.capacity.warn') !== null")
        page.text(".capacity") shouldContain "tövsiyədən (41) çoxdur"
        page.locator("[data-field=target] input").fill("staging.portal.example")
        page.locator("button", Page.LocatorOptions().setHasText("Kəşf et")).first().click()
        page.waitFor("() => document.querySelector('[data-field=target].field.invalid') !== null")
        page.text(".field-error[data-field=target]") shouldContain "http"

        page.locator("[data-field=target] input").fill("https://staging.portal.example")
        page.locator("button", Page.LocatorOptions().setHasText("Kəşf et")).first().click()
        page.waitFor("() => location.hash === '#/kesfiyyat'")
        page.waitFor(
            "() => document.querySelector('.run-banner .badge') && document.querySelector('.run-banner .badge').textContent === 'Gedir'",
        )
    }

    @Test
    fun `a draft scenario can be approved and its diff to the approved version is shown`() {
        serve()
        val page = open("ssenariler?id=scn_core_v3")
        page.waitFor("() => document.querySelector('.scn-version[aria-selected=true]')?.dataset.id === 'scn_core_v3'")

        page.locator("button[role=tab]", Page.LocatorOptions().setHasText("Fərq")).click()
        page.waitFor("() => document.querySelectorAll('.diff .dl.ADDED').length === 2")
        page.locator(".card-head .btn.primary", Page.LocatorOptions().setHasText("Təsdiqlə")).click()
        page.waitFor(
            "() => [...document.querySelectorAll('.scn-version[data-id=scn_core_v2] .badge')].some(b => b.textContent === 'Köhnəlib')",
        )
    }

    @Test
    fun `screenshots of every screen, desktop and mobile, light and dark`() {
        serve()
        clock.advance(STEP_GAP)
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            for ((width, height, device) in listOf(
                Triple(DESKTOP_WIDTH, DESKTOP_HEIGHT, "desktop"),
                Triple(MOBILE_WIDTH, MOBILE_HEIGHT, "mobile"),
            )) {
                for ((route, ready) in SCREENS) {
                    val page = open(route, width, height, dark)
                    page.waitFor(ready)
                    if (route == "agentler" && device == "desktop") page.loadEveryThumbnail()
                    page.settle()
                    page.shoot("$route-$device-$theme", full = device == "desktop")
                    page.context().close()
                    contexts.removeAt(contexts.lastIndex)
                }
            }
        }
        val menu = open("agentler", MOBILE_WIDTH, MOBILE_HEIGHT)
        menu.waitFor(SCREENS.getValue("agentler"))
        menu.locator("#menuBtn").click()
        menu.waitFor("() => document.getElementById('app').classList.contains('menu-open')")
        menu.waitForTimeout(SETTLE_MILLIS)
        menu.shoot("menu-mobile-light", full = false)
        Files.list(SHOTS).use { files -> files.filter { it.toString().endsWith(".png") }.count().toInt() } shouldBeGreaterThan 24
    }

    companion object {
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

        @BeforeAll
        @JvmStatic
        fun launch() {
            playwright = Playwright.create()
            browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        }

        @AfterAll
        @JvmStatic
        fun close() {
            browser.close()
            playwright.close()
        }

        val SHOTS: Path = Path.of("build", "panel-screenshots").toAbsolutePath()
        private const val DESKTOP_WIDTH = 1440
        private const val DESKTOP_HEIGHT = 900
        private const val MOBILE_WIDTH = 390
        private const val MOBILE_HEIGHT = 844
        private const val MOBILE_BELOW = 768
        private const val WAIT_MILLIS = 15_000.0
        private const val SETTLE_MILLIS = 300.0
        private val STEP_GAP = 4_000.milliseconds

        /** Each screen with the condition that tells its data has arrived. */
        private val SCREENS =
            linkedMapOf(
                "telimat" to
                    "() => document.querySelector('.capacity:not(.unknown)') !== null && document.querySelectorAll('.flow-step').length === 4",
                "kesfiyyat" to
                    "() => document.querySelectorAll('.visit').length === 16 && document.querySelector('.current-shot img')?.complete",
                "ssenariler" to
                    "() => document.querySelectorAll('.scn-version').length === 5 && document.querySelectorAll('.verdict').length === 3 && document.querySelector('.code') !== null",
                "orkestrator" to
                    "() => document.querySelectorAll('.lane').length === 9 && document.querySelectorAll('.event-row').length === 1",
                "agentler" to
                    "() => document.querySelectorAll('.agent').length === 40 && document.querySelectorAll('.timeline .entry').length > 0",
                "hesabatlar" to
                    "() => document.querySelectorAll('.screen:not([hidden]) tbody tr').length === 6 && document.querySelectorAll('.stab-card').length === 1",
            )
    }
}
