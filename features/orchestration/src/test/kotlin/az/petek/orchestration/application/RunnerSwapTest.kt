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
import az.petek.agent.domain.FailureReason
import az.petek.browser.domain.BrowserProxy
import az.petek.campaign.domain.StepPhase
import az.petek.evidence.domain.StepStatus
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The account swap (Faza 18) composed with events, setup and failed testers (Faza 24.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerSwapTest {
    private val ok = ActionOutcome(ActionStatus.SUCCEEDED, "ok")

    private val swap = RunOptions(swapAccounts = true)

    @Test
    fun `a swapped account starts on the site's home page, never on a blank page`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))

            f.runner().run(campaign(managers = 0, employees = 2, steps = listOf(step("look", employees()))), swap)

            f.evidence.stepList
                .filter { it.action == "swap_open" }
                .map { it.agentId?.value to it.status } shouldContainExactly
                listOf("a02" to StepStatus.PASSED, "a03" to StepStatus.PASSED, "a01" to StepStatus.PASSED)
            listOf("a01", "a02", "a03").forEach {
                f.browser
                    .session(it)
                    .actions
                    .first() shouldBe "navigate /"
            }
        }

    @Test
    fun `in the swap a waiter never takes the event the first pass published`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "post@swap") {
                    ActionOutcome(ActionStatus.FAILED, "could not post", failureReason = FailureReason.PROBLEM_REPORTED)
                } else {
                    ok
                }
            }
            val posting =
                campaign(
                    managers = 0,
                    employees = 2,
                    steps =
                        listOf(
                            step("post", admin(), emits = "note_posted"),
                            step("read", employees(), waitFor = "note_posted", waitTimeout = 5.seconds),
                        ),
                )

            f.runner().run(posting, swap)

            val waits = f.evidence.stepList.filter { it.action.startsWith("wait_for") }
            waits.filter { it.scenarioStep == "read" }.map { it.status }.toSet() shouldBe setOf(StepStatus.PASSED)
            // Nothing was posted in the swap: its readers must not see the first pass's note as theirs.
            waits.filter { it.scenarioStep == "read@swap" }.map { it.status } shouldBe List(2) { StepStatus.FAILED }
        }

    @Test
    fun `an event of a setup step still serves the main steps in the swap`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val prepared =
                campaign(
                    managers = 0,
                    employees = 2,
                    setup = listOf(step("prepare", admin(), emits = "ready", phase = StepPhase.SETUP)),
                    steps = listOf(step("read", employees(), waitFor = "ready", waitTimeout = 5.seconds)),
                )

            f.runner().run(prepared, swap)

            f.evidence.stepList
                .filter { it.action.startsWith("wait_for") }
                .map { it.scenarioStep to it.status } shouldContainExactly
                List(2) { "read" to StepStatus.PASSED } + List(2) { "read@swap" to StepStatus.PASSED }
        }

    @Test
    fun `a tester that failed a main step sits out the swap, saying why`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "look" && call.agentId.value == "a03") ActionOutcome(ActionStatus.FAILED, "broken") else ok
            }

            f.runner().run(campaign(managers = 0, employees = 3, steps = listOf(step("look", employees()))), swap)

            f.evidence.stepList
                .filter { it.action == "swap_accounts" }
                .map { it.status to it.detail } shouldContainExactly
                listOf(
                    StepStatus.SKIPPED to "a03 failed a main step and sits out the swap",
                    StepStatus.PASSED to "tester a01 continues with the account of a02 (employee) in a new browser",
                    StepStatus.PASSED to "tester a02 continues with the account of a04 (employee) in a new browser",
                    StepStatus.PASSED to "tester a04 continues with the account of a01 (admin) in a new browser",
                )
            f.evidence.stepList
                .filter { it.scenarioStep == "look@swap" && it.action.startsWith("do") }
                .map { it.agentId?.value }
                .toSet() shouldBe setOf("a02", "a04")
        }

    @Test
    fun `a swapped account opens again through the proxy it had, so the site sees it from one address`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val proxies = (1..3).map { BrowserProxy("http://10.0.0.$it:3128") }
            val settings = RunnerSettings(mailDomain = "test.example.test", storageRoot = Path.of("build", "storage"), proxies = proxies)

            f.runner(settings = settings).run(
                campaign(managers = 0, employees = 2, steps = listOf(step("look", employees()))),
                swap.copy(ownSite = true),
            )

            val opened = f.browser.opened.groupBy({ it.label }, { it.proxy?.server })
            opened.values.forEach { it.size shouldBe 2 }
            opened.mapValues { it.value.toSet() } shouldBe
                mapOf(
                    "a01" to setOf("http://10.0.0.1:3128"),
                    "a02" to setOf("http://10.0.0.2:3128"),
                    "a03" to setOf("http://10.0.0.3:3128"),
                )
        }

    @Test
    fun `with waves the swap is not done, and the run says so instead of dropping it silently`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val base = campaign(managers = 0, employees = 4, steps = listOf(step("look", employees())))

            f.runner().run(base.copy(settings = base.settings.copy(waveSize = 2)), swap)

            f.evidence.stepList
                .filter { it.action == "swap_accounts" }
                .map { it.status to it.detail } shouldContainExactly
                listOf(StepStatus.SKIPPED to "the account swap is not done in a run with waves (campaign.wave_size)")
            f.evidence.stepList.none { it.scenarioStep == "look@swap" } shouldBe true
        }
}
