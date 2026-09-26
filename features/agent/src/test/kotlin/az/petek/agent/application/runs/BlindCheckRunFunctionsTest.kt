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
import az.petek.agent.domain.FailureReason
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.RunFunctionFixture
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.PageHealth
import az.petek.browser.domain.SlowResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

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
