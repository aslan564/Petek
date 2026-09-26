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

package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.browser.domain.BrowserProxy
import az.petek.evidence.domain.StepStatus
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Capacity (Faza 21): waves with their own browsers and live events, one proxy per live tester, 429 as a set-up gap. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerWavesTest {
    @Test
    fun `testers run in waves of the given size, never more live browsers than a wave, events only within the wave`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val maxLive = AtomicInteger()
            f.agents.script = { _, runtime ->
                maxLive.updateAndGet {
                    maxOf(
                        it,
                        f.browser.sessions.values
                            .count { !it.closed },
                    )
                }
                ActionOutcome(ActionStatus.SUCCEEDED, "done ${runtime.identity.agentId}")
            }
            val base =
                campaign(
                    managers = 0,
                    employees = 4,
                    steps =
                        listOf(
                            step("post", admin(), emits = "note_posted"),
                            step("read", employees(), waitFor = "note_posted", waitTimeout = 5.seconds),
                        ),
                )
            val waved = base.copy(settings = base.settings.copy(waveSize = 3))

            val summary = f.runner().run(waved)

            val waves =
                f.evidence.stepList
                    .filter { it.action == "wave" }
                    .map { it.detail }
            waves shouldContainExactly listOf("wave 1 of 2: 3 testers (a01..a03)", "wave 2 of 2: 2 testers (a04..a05)")
            maxLive.get() shouldBe 3
            // The admin (wave 1) posts; wave 1's readers get it, wave 2's readers do not: no event crosses waves.
            val read = f.evidence.stepList.filter { it.scenarioStep == "read" && it.action.startsWith("wait_for") }
            read.filter { it.agentId?.value in setOf("a02", "a03") }.map { it.status }.toSet() shouldBe setOf(StepStatus.PASSED)
            read.filter { it.agentId?.value in setOf("a04", "a05") }.map { it.status }.toSet() shouldBe setOf(StepStatus.FAILED)
            summary.outcome shouldBe RunOutcome.FAILED
            f.buses.size shouldBe 2
        }

    @Test
    fun `each live tester goes out through its own proxy, tester n of a wave through proxy n`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val proxies = (1..3).map { BrowserProxy("http://10.0.0.$it:3128") }
            val base = campaign(managers = 0, employees = 4, steps = listOf(step("look", employees())))
            val waved = base.copy(settings = base.settings.copy(waveSize = 3))

            f.runner(settings = settings(proxies)).run(waved).outcome shouldBe RunOutcome.PASSED

            f.browser.opened.map { it.label to it.proxy?.server } shouldContainExactly
                listOf(
                    "a01" to "http://10.0.0.1:3128",
                    "a02" to "http://10.0.0.2:3128",
                    "a03" to "http://10.0.0.3:3128",
                    "a04" to "http://10.0.0.1:3128",
                    "a05" to "http://10.0.0.2:3128",
                ).sortedBy { it.first }
        }

    @Test
    fun `fewer proxies than live testers stop the run before a browser opens, saying so`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))

            val summary =
                f
                    .runner(
                        settings = settings(listOf(BrowserProxy("http://10.0.0.1:3128"))),
                    ).run(campaign(managers = 0, employees = 2))

            summary.outcome shouldBe RunOutcome.ABORTED
            f.browser.opened shouldBe emptyList()
            f.evidence.stepList
                .single { it.action == "abort" }
                .detail
                .orEmpty() shouldContain "only 1 proxies are given"
        }

    @Test
    fun `an action the site answered with 429 is a rate limit of the shared IP, not the agent's conclusion`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            f.agents.script = { _, runtime ->
                f.browser.session(runtime.identity.agentId.value).mutated("POST", "/register", 429)
                ActionOutcome(ActionStatus.FAILED, "the form did not submit")
            }

            f.runner().run(campaign(managers = 0, employees = 1, steps = listOf(step("sign", employees()))))

            val record =
                f.evidence.stepList.single {
                    it.scenarioStep == "sign" && it.agentId?.value == "a02" &&
                        it.action.startsWith(
                            "do",
                        )
                }
            record.detail.orEmpty() shouldStartWith "rate_limited: The site answered 429 Too Many Requests"
        }

    @Test
    fun `with the swap allowed, finished testers hand their accounts on in a ring and the main steps run again`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val base = campaign(managers = 0, employees = 2, steps = listOf(step("look", employees())))

            val summary = f.runner().run(base, RunOptions(swapAccounts = true))

            summary.outcome shouldBe RunOutcome.PASSED
            f.evidence.stepList
                .filter { it.action == "swap_accounts" }
                .map { it.detail } shouldContainExactly
                listOf(
                    "tester a01 continues with the account of a02 (employee) in a new browser",
                    "tester a02 continues with the account of a03 (employee) in a new browser",
                    "tester a03 continues with the account of a01 (admin) in a new browser",
                )
            f.evidence.stepList
                .filter {
                    it.scenarioStep == "look@swap" &&
                        it.action.startsWith(
                            "do",
                        )
                }.map { it.agentId?.value }
                .toSet() shouldBe
                setOf("a02", "a03")
            // Every account is in exactly one browser at a time: the old ones were closed before the new ones opened.
            f.browser.opened.count { it.label == "a02" } shouldBe 2
            f.browser.sessions.values
                .count { !it.closed } shouldBe 0
        }

    private fun settings(proxies: List<BrowserProxy>) =
        RunnerSettings(mailDomain = "test.example.test", storageRoot = Path.of("build", "storage"), proxies = proxies)
}
