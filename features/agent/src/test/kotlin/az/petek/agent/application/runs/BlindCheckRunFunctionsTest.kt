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

package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.ActorShare
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.Looks
import az.petek.agent.testing.RunFunctionFixture
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.LookArea
import az.petek.browser.domain.LookAreaReason
import az.petek.browser.domain.LookRequest
import az.petek.browser.domain.LookSelector
import az.petek.browser.domain.LookShotKind
import az.petek.browser.domain.PageAnchor
import az.petek.browser.domain.PageHealth
import az.petek.browser.domain.PageLook
import az.petek.browser.domain.PageTiming
import az.petek.browser.domain.SlowResponse
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.VisualProfile
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.LookAnchor
import az.petek.evidence.domain.LookBox
import az.petek.evidence.domain.LookFrameKind
import az.petek.evidence.domain.LookMask
import az.petek.evidence.domain.LookMaskReason
import az.petek.evidence.domain.StepStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `site_health` and `direct_url`: blind checks decided by code from what the browser saw (Faza 13). */
class BlindCheckRunFunctionsTest {
    private val fixture = RunFunctionFixture(AgentTestData.itEmployee, contractSite = false)
    private val browser = fixture.browser

    @Test
    fun `a clean page passes every check`() =
        runTest {
            browser.pageLinks["/"] = listOf("/about")
            browser.httpResponses["GET /about"] = HttpProbeResult(200, "")

            val outcome = fixture.run("site_health", mapOf("checks" to "links,console,slow,mobile"))

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "nothing wrong"
        }

    @Test
    fun `perf records how fast each page became usable on each screen, and never fails the step`() =
        runTest {
            browser.pageTimingByUrl["/"] = PageTiming(120, 480, 900, 1_400, 0.02)
            browser.pageTimingByUrl["/about"] = PageTiming(90, 300, 600, null, 0.0)

            val outcome =
                fixture.run(
                    "site_health",
                    mapOf(
                        "checks" to "perf",
                        "pages" to "/,/about",
                        "share" to "work",
                        "devices" to "phone",
                    ),
                )

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.evidence.pageTimingList.map { listOf(it.page, it.device, it.loadMs, it.largestPaintMs) } shouldBe
                listOf(listOf("/", "phone", 900L, 1_400L), listOf("/about", "phone", 600L, null))
            fixture.evidence.pageTimingList.first().let {
                it.scenarioStep shouldBe "setup-site_health"
                it.layoutShift shouldBe 0.02
                fixture.evidence.stepList
                    .single { step -> step.stepId == it.stepId }
                    .action shouldContain "read the timing of /"
            }
        }

    @Test
    fun `look records a page look per page and screen, its frames visual artifacts of the look sub-action, and never fails`() =
        runTest {
            val clock = LookArea(10, 20, 80, 18, LookAreaReason.TIME_TEXT, "time")
            val name = LookArea(0, 40, 120, 18, LookAreaReason.RUN_TEXT, RunTexts.TESTER_NAME)
            browser.lookByUrl["/"] =
                Looks.look(
                    "/",
                    LookShotKind.MAIN,
                    LookShotKind.MOVED,
                    areas = listOf(clock, name),
                    anchors = listOf(PageAnchor("[data-testid=\"news\"]", 0, 100, 375, 300)),
                )
            // A page that answers 500 and never settles is still only looked at: comparing it is the report's work.
            browser.lookByUrl["/about"] =
                Looks.look("/about", LookShotKind.RELOADED, LookShotKind.MAIN, status = 500, settled = false, unsettled = listOf("network"))

            val outcome =
                fixture.run(
                    "site_health",
                    mapOf("checks" to "look", "pages" to "/,/about", "share" to "work", "devices" to "phone,desktop"),
                )

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "Kept 4 look(s)."
            val looks = fixture.evidence.pageLookList
            looks.map { it.page to it.device } shouldBe listOf("/" to "phone", "/" to "desktop", "/about" to "phone", "/about" to "desktop")
            looks.forEach { look ->
                look.scenarioStep shouldBe "setup-site_health"
                look.maxHeight shouldBe LookRequest.DEFAULT_MAX_HEIGHT
                look.testers shouldBe 1
                look.frames.first().kind shouldBe LookFrameKind.MAIN
                val step = fixture.evidence.stepList.single { it.stepId == look.stepId }
                step.action shouldBe "site_health: look at ${look.page} (${look.device})"
                step.status shouldBe StepStatus.PASSED
                val artifacts = fixture.evidence.artifactList.filter { it.stepId == look.stepId }
                artifacts.map { it.type }.toSet() shouldBe setOf(ArtifactType.VISUAL)
                look.frames.map { it.artifactId } shouldBe artifacts.map { it.artifactId }
            }
            looks.first().let { home ->
                home.frames.map { it.kind } shouldBe listOf(LookFrameKind.MAIN, LookFrameKind.MOVED)
                home.frames.first().masks shouldBe
                    listOf(
                        LookMask(LookBox(10, 20, 80, 18), LookMaskReason.TIME_TEXT, "time"),
                        LookMask(LookBox(0, 40, 120, 18), LookMaskReason.RUN_TEXT, RunTexts.TESTER_NAME),
                    )
                home.anchors shouldBe listOf(LookAnchor("[data-testid=\"news\"]", LookBox(0, 100, 375, 300)))
                home.renderer shouldBe "chromium 141.0; Test OS x64; headless"
                home.fonts shouldBe listOf("Inter 400 normal")
                String(fixture.artifacts.contents.getValue(home.frames.first().artifactId)) shouldBe "visual:/:MAIN"
                fixture.evidence.stepList
                    .single { it.stepId == home.stepId }
                    .detail shouldStartWith
                    "kept moving within the load; 375x2310 of a 2310 px page on a 375x812 screen; masked: 1 run text, 1 time text"
            }
            looks.last().let { about ->
                about.frames.map { it.kind } shouldBe listOf(LookFrameKind.MAIN, LookFrameKind.RELOADED)
                about.status shouldBe 500
                about.settled shouldBe false
                about.unsettled shouldBe listOf("network")
                fixture.evidence.stepList
                    .single { it.stepId == about.stepId }
                    .detail shouldBe
                    "changed on reload; 375x2310 of a 2310 px page on a 375x812 screen; answered 500; not settled: network"
            }
            // The step's own screenshot is still a screenshot of the page, never a look.
            fixture.artifactsOf(ArtifactType.SCREENSHOT).shouldNotBeEmpty()
        }

    @Test
    fun `look is not one of the default checks`() =
        runTest {
            browser.pageLook = Looks.look("/")

            fixture.run("site_health", mapOf("pages" to "/")).status shouldBe ActionStatus.SUCCEEDED

            browser.lookRequests.shouldBeEmpty()
            SiteHealthRunFunction.DEFAULT_CHECKS shouldBe SiteHealthRunFunction.ALL_CHECKS - "look"
        }

    @Test
    fun `the look asks to mask the profile's and the step's selectors and the run's own texts, never the password`() =
        runTest {
            val target =
                TargetProfile.DEFAULT.copy(
                    selectors = mapOf("visual.clock" to "[data-testid=\"server-clock\"]", "visual.ticker" to "aside .ticker"),
                    visual = VisualProfile(listOf("visual.clock", "footer .ads")),
                )
            val fixture = RunFunctionFixture(AgentTestData.itEmployee, target = target, contractSite = false)
            fixture.shared.put(SharedRunState.COMPANY_CODE, "PTK-4821")
            fixture.browser.pageLook = Looks.look("/")

            val outcome =
                fixture.run(
                    "site_health",
                    mapOf(
                        "checks" to "look",
                        "look_mask" to "visual.ticker",
                        "look_max_height" to "0",
                        "look_loads" to "1",
                        "look_settle_ms" to "1500",
                    ),
                )

            outcome.status shouldBe ActionStatus.SUCCEEDED
            val request = fixture.browser.lookRequests.single()
            request.selectors shouldBe
                listOf(
                    LookSelector("visual.clock", "[data-testid=\"server-clock\"]"),
                    LookSelector("footer .ads", "footer .ads"),
                    LookSelector("look_mask:visual.ticker", "aside .ticker"),
                )
            request.maxHeight shouldBe 0
            request.loads shouldBe 1
            request.settle shouldBe 1_500.milliseconds
            request.runTag shouldBe RunTags.forRun(AgentTestData.RUN_ID).value
            val texts = request.runTexts.map { it.text }
            texts shouldContainAll listOf(AgentTestData.itEmployee.displayName, AgentTestData.itEmployee.email, "PTK-4821")
            texts shouldContain AgentTestData.admin.email
            AgentTestData.roster.forEach { texts shouldNotContain it.password.reveal() }
            request.toString() shouldNotContain AgentTestData.itEmployee.displayName
            fixture.evidence.pageLookList
                .single()
                .maxHeight shouldBe 0
            fixture.assertPasswordNotRecorded()
        }

    @Test
    fun `a look_mask that names no selector key, or a Playwright-only selector, is a missing prerequisite`() =
        runTest {
            val target = TargetProfile.DEFAULT.copy(selectors = mapOf("visual.banner" to "div.banner >> nth=0"))
            val fixture = RunFunctionFixture(AgentTestData.itEmployee, target = target, contractSite = false)

            suspend fun refused(vararg args: Pair<String, String>): String {
                val outcome = fixture.run("site_health", mapOf("checks" to "look,console") + args)
                outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
                return outcome.summary
            }

            refused("look_mask" to ".ticker") shouldContain "there is no key '.ticker'"
            refused("look_mask" to "visual.banner") shouldContain "uses '>>' that only Playwright understands"
            refused("look_max_height" to "20000") shouldContain "look_max_height is a whole number of CSS pixels"
            refused("look_settle_ms" to "soon") shouldContain "look_settle_ms is a whole number of milliseconds from 500 to 15000"
            refused("look_loads" to "3") shouldContain "look_loads is the number of loads from 1 to 2, not '3'"
            fixture.browser.actions.none { it.startsWith("navigate") } shouldBe true
            // Look arguments are read only when a look is asked for.
            fixture.run("site_health", mapOf("checks" to "console", "look_mask" to ".ticker")).status shouldBe ActionStatus.SUCCEEDED
        }

    @Test
    fun `a session that cannot take a look leaves a skipped sub-action and the step passes`() =
        runTest {
            val outcome = fixture.run("site_health", mapOf("checks" to "look,console", "pages" to "/"))

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "Kept 0 look(s); 1 could not be taken or kept."
            val look = fixture.steps.single { it.action == "site_health: look at /" }
            look.status shouldBe StepStatus.SKIPPED
            look.detail shouldBe "not captured: this session cannot take looks"
            fixture.evidence.pageLookList.shouldBeEmpty()
            fixture.artifactsOf(ArtifactType.VISUAL).shouldBeEmpty()

            browser.lookFailure = BrowserActionException("Target page, context or browser has been closed")
            fixture.run("site_health", mapOf("checks" to "look", "pages" to "/")).status shouldBe ActionStatus.SUCCEEDED
            fixture.steps.last { it.action == "site_health: look at /" }.let {
                it.status shouldBe StepStatus.SKIPPED
                it.detail shouldBe "not captured: Target page, context or browser has been closed"
            }
        }

    @Test
    fun `a look that times out is skipped`() =
        runTest {
            val fixture =
                RunFunctionFixture(AgentTestData.itEmployee, contractSite = false, session = { browser ->
                    object : BrowserSession by browser {
                        override suspend fun look(request: LookRequest): PageLook? {
                            delay(RunTrace.LOOK_TIMEOUT + 1.seconds)
                            return Looks.look("/")
                        }
                    }
                })

            val outcome = fixture.run("site_health", mapOf("checks" to "look", "pages" to "/"))

            outcome.status shouldBe ActionStatus.SUCCEEDED
            fixture.steps.single { it.action == "site_health: look at /" }.let {
                it.status shouldBe StepStatus.SKIPPED
                it.detail shouldBe "not captured: timed out after 30s"
            }
            fixture.evidence.pageLookList.shouldBeEmpty()
        }

    @Test
    fun `a look whose frames cannot all be written is not kept, and is counted as missed`() =
        runTest {
            var visualWrites = 0
            val fixture =
                RunFunctionFixture(AgentTestData.itEmployee, contractSite = false, artifactStore = { store ->
                    object : ArtifactStore by store {
                        override suspend fun write(
                            runId: RunId,
                            stepId: StepId,
                            owner: String,
                            type: ArtifactType,
                            bytes: ByteArray,
                        ): ArtifactRecord {
                            if (type == ArtifactType.VISUAL && ++visualWrites == 2) throw IOException("No space left on device")
                            return store.write(runId, stepId, owner, type, bytes)
                        }
                    }
                })
            fixture.browser.pageLook = Looks.look("/", LookShotKind.MAIN, LookShotKind.RELOADED)

            val outcome = fixture.run("site_health", mapOf("checks" to "look", "pages" to "/"))

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "Kept 0 look(s); 1 could not be taken or kept."
            // No record names a look whose frames are not all there (AGENTS.md rule 5), and the evidence says why.
            fixture.evidence.pageLookList.shouldBeEmpty()
            fixture.artifactsOf(ArtifactType.VISUAL) shouldHaveSize 1
            fixture.steps.single { it.action == "site_health: keep the look of /" }.let {
                it.status shouldBe StepStatus.SKIPPED
                it.detail shouldBe "not kept: No space left on device"
            }
            // The frames that were written belong to the look's own sub-action, which says what it saw.
            val look = fixture.steps.single { it.action == "site_health: look at /" }
            fixture.artifactsOf(ArtifactType.VISUAL).single().stepId shouldBe look.stepId
        }

    @Test
    fun `a tester dealt the same job a fourth time does not look`() =
        runTest {
            browser.pageLook = Looks.look("/")
            val oneJob = mapOf("checks" to "look", "pages" to "/", "share" to "work", "devices" to "phone")

            fixture.run("site_health", oneJob, share = ActorShare(3, 4)).status shouldBe ActionStatus.SUCCEEDED
            browser.lookRequests.shouldBeEmpty()
            fixture.run("site_health", oneJob, share = ActorShare(2, 4))
            browser.lookRequests shouldHaveSize 1
            // Without share every tester checks every page: only the first three look.
            fixture.run("site_health", mapOf("checks" to "look", "pages" to "/,/about"), share = ActorShare(3, 5))
            browser.lookRequests shouldHaveSize 1
            fixture.run("site_health", mapOf("checks" to "look", "pages" to "/,/about"), share = ActorShare(0, 5))
            browser.lookRequests shouldHaveSize 3
            fixture.evidence.pageLookList.map { it.testers } shouldBe listOf(4, 5, 5)
        }

    @Test
    fun `console errors repeated by the look's reload are listed once`() =
        runTest {
            browser.pageLook = Looks.look("/", LookShotKind.MAIN, LookShotKind.RELOADED)
            browser.pageHealth =
                PageHealth(
                    consoleErrors = listOf("TypeError: x is undefined", "TypeError: x is undefined"),
                    failedRequests = listOf("GET /api/stats -> 500", "GET /api/stats -> 500"),
                    slowResponses = listOf(SlowResponse("GET", "/api/report", 4_200), SlowResponse("GET", "/api/report", 5_100)),
                )

            val outcome = fixture.run("site_health", mapOf("checks" to "console,slow,look", "pages" to "/"))

            outcome.failureReason shouldBe FailureReason.UNHEALTHY_PAGE
            outcome.summary shouldStartWith "3 problem(s): "
            Regex("console error: TypeError").findAll(outcome.summary).count() shouldBe 1
            Regex("request failed: GET /api/stats").findAll(outcome.summary).count() shouldBe 1
            outcome.summary shouldContain "GET /api/report took 5100 ms"
            outcome.summary shouldNotContain "4200"
        }

    @Test
    fun `broken links, console errors, slow requests and a too wide page are each reported`() =
        runTest {
            browser.pageLinks["/"] = listOf("/about", "/gone")
            browser.httpResponses["GET /about"] = HttpProbeResult(200, "")
            browser.httpResponses["GET /gone"] = HttpProbeResult(404, "")
            browser.pageHealth =
                PageHealth(
                    consoleErrors = listOf("TypeError: x is undefined"),
                    failedRequests = listOf("GET /api/stats -> 500"),
                    slowResponses = listOf(SlowResponse("GET", "/api/report", 4_200), SlowResponse("GET", "/api/fast", 80)),
                )
            browser.overflow = 120

            val outcome = fixture.run("site_health", mapOf("checks" to "links,console,slow,mobile"))

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.UNHEALTHY_PAGE
            outcome.summary shouldContain "/ links to /gone, which answers 404"
            outcome.summary shouldContain "console error: TypeError: x is undefined"
            outcome.summary shouldContain "request failed: GET /api/stats -> 500"
            outcome.summary shouldContain "GET /api/report took 4200 ms"
            outcome.summary shouldNotContain "/api/fast"
            outcome.summary shouldContain "120 px wider than a 375px screen"
        }

    @Test
    fun `the browser ends on the first page that went wrong, so the evidence shows it`() =
        runTest {
            browser.onAction = { action ->
                if (action.startsWith("navigate ")) browser.overflow = if (action == "navigate /about") 120 else 0
            }

            val outcome = fixture.run("site_health", mapOf("checks" to "mobile", "pages" to "/about,/"))

            outcome.failureReason shouldBe FailureReason.UNHEALTHY_PAGE
            val shots =
                fixture.artifacts.contents.values
                    .map { String(it) }
                    .filter { it.startsWith("png:") }
            shots.shouldNotBeEmpty()
            shots.forEach { it shouldContain "/about" }
        }

    @Test
    fun `a page the site redirects to its slash address still passes the back button check`() =
        runTest {
            browser.pageLinks["/docs/"] = listOf("/", "/docs/")
            browser.onAction = { action ->
                // The site answers /docs with /docs/, the way a static host does for a folder.
                if (action == "navigate /docs") browser.url = "/docs/"
                if (action == "back" && browser.url == "/docs") browser.url = "/docs/"
            }

            val outcome = fixture.run("site_health", mapOf("checks" to "back", "pages" to "/docs"))

            outcome.status shouldBe ActionStatus.SUCCEEDED
        }

    @Test
    fun `an unknown check is refused before anything is opened`() =
        runTest {
            val outcome = fixture.run("site_health", mapOf("checks" to "links,colour"))

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            browser.actions.none { it.startsWith("navigate") } shouldBe true
        }

    @Test
    fun `someone else's object that answers 404 is refused, one that is shown is a finding`() =
        runTest {
            browser.httpResponses["GET /notes/7"] = HttpProbeResult(404, "")
            fixture.run("direct_url", mapOf("path" to "/notes/7")).status shouldBe ActionStatus.SUCCEEDED

            browser.httpResponses["GET /notes/8"] = HttpProbeResult(200, "Alış-veriş")
            val shown = fixture.run("direct_url", mapOf("path" to "/notes/8"))

            shown.status shouldBe ActionStatus.FAILED
            shown.failureReason shouldBe FailureReason.ACCESS_NOT_REFUSED
        }
}
