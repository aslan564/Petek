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
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.StepPhase
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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
    fun `testers run in waves with the admin in every one, so every wave's readers get their own post`() =
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
            val waved = base.copy(settings = base.settings.copy(waveSize = 2))

            val summary = f.runner().run(waved)

            f.evidence.stepList
                .filter { it.action == "wave" }
                .map { it.detail } shouldContainExactly
                listOf(
                    "wave 1 of 2: 2 testers (a02, a04); in every wave: a01 (admin)",
                    "wave 2 of 2: 2 testers (a03, a05); in every wave: a01 (admin), set up in wave 1",
                )
            // The admin is the only one of its role: live in both waves, its browser opened once and kept.
            maxLive.get() shouldBe 3
            f.browser.opened.count { it.label == "a01" } shouldBe 1
            // It posts in each wave, on that wave's bus; every reader gets its own wave's post, none is skipped.
            f.evidence.eventList.map { it.name } shouldContainExactly listOf("note_posted", "note_posted")
            f.evidence.stepList
                .filter { it.scenarioStep == "read" && it.action.startsWith("wait_for") }
                .map { it.agentId?.value to it.status } shouldContainExactly
                listOf("a02", "a04", "a03", "a05").map { it to StepStatus.PASSED }
            f.evidence.stepList.none { it.action == "coverage" } shouldBe true
            summary.outcome shouldBe RunOutcome.PASSED
            f.buses.size shouldBe 2
        }

    @Test
    fun `the admin's setup runs in the first wave only and its setup event serves every wave`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val base =
                campaign(
                    managers = 0,
                    employees = 4,
                    setup =
                        listOf(
                            step("prepare", admin(), emits = "ready", phase = StepPhase.SETUP),
                            setupStep("join", employees()),
                        ),
                    steps = listOf(step("read", employees(), waitFor = "ready", waitTimeout = 5.seconds)),
                )

            val summary = f.runner().run(base.copy(settings = base.settings.copy(waveSize = 2)))

            f.agents.callsFor("prepare").map { it.agentId.value } shouldContainExactly listOf("a01")
            f.agents
                .callsFor("join")
                .map { it.agentId.value }
                .sorted() shouldContainExactly listOf("a02", "a03", "a04", "a05")
            f.evidence.stepList
                .filter { it.scenarioStep == "read" && it.action.startsWith("wait_for") }
                .map { it.status }
                .toSet() shouldBe setOf(StepStatus.PASSED)
            // Wave 2's bus starts with the very event of wave 1: the same id, recorded once.
            val ready = f.buses[0].latest("ready").shouldNotBeNull()
            f.buses[1].latest("ready")?.eventId shouldBe ready.eventId
            f.evidence.eventList.map { it.name } shouldContainExactly listOf("ready")
            summary.outcome shouldBe RunOutcome.PASSED
        }

    @Test
    fun `a step no wave can check with its emitter is not covered, and the run says so`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val base =
                campaign(
                    managers = 2,
                    employees = 2,
                    steps =
                        listOf(
                            step("post", managers(), emits = "note_posted"),
                            step("read", employees(), waitFor = "note_posted", waitTimeout = 5.seconds),
                        ),
                )

            // Waves of one: a manager posts alone in its wave, every reader is in a wave without a manager.
            val summary = f.runner().run(base.copy(settings = base.settings.copy(waveSize = 1)))

            val coverage = f.evidence.stepList.single { it.action == "coverage" }
            coverage.status shouldBe StepStatus.FAILED
            coverage.detail.orEmpty() shouldStartWith "not_covered: 0 of 2 receivers could wait for the event of 'read'"
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `a race whose event has no emitter in the wave is skipped, not failed as a race nobody ran`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val base =
                campaign(
                    managers = 2,
                    employees = 2,
                    steps =
                        listOf(
                            step("ticket", employees(), emits = "ticket_created"),
                            step(
                                "race",
                                managers(),
                                waitFor = "ticket_created",
                                parallel = true,
                                assertions = listOf(AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", ".*/approve"))),
                            ),
                        ),
                )

            // The two racing managers share wave 1, the employees who write the ticket are wave 2.
            f.runner().run(base.copy(settings = base.settings.copy(waveSize = 2)))

            f.verify.groupCalls.shouldBeEmpty()
            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.SKIPPED
            f.evidence.stepList
                .single { it.action == "coverage" }
                .detail
                .orEmpty() shouldStartWith "not_covered:"
        }

    @Test
    fun `a race with more racers than a wave holds is split, and the wave left with one racer never passes`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "race") {
                    val status = if (call.agentId.value == "a03") 409 else 303
                    f.browser.session(call.agentId.value).mutated("POST", "/tickets/t1/approve", status)
                }
                ActionOutcome(ActionStatus.SUCCEEDED, "approved")
            }
            val race = AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", ".*/approve"))
            val base =
                campaign(
                    managers = 3,
                    employees = 2,
                    steps = listOf(step("race", managers(), parallel = true, assertions = listOf(race))),
                )
            // Three racers, waves of two: a02 and a03 race in wave 1, a04 is alone in wave 2.
            val waved = base.copy(settings = base.settings.copy(waveSize = 2))

            val summary = f.runner().run(waved)

            f.verify.groupCalls.map { results -> results.map { it.agentId.value } } shouldContainExactly
                listOf(listOf("a02", "a03"), listOf("a04"))
            f.evidence.assertionList
                .filter { it.type == "only_one_succeeds" }
                .map { it.verdict to it.note } shouldContainExactly
                listOf(
                    Verdict.PASSED to null,
                    Verdict.FAILED to "a race needs at least 2 racing actors; only a04 raced",
                )
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `each live tester goes out through its own proxy, the admin keeping the first in every wave`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val proxies = (1..3).map { BrowserProxy("http://10.0.0.$it:3128") }
            val base = campaign(managers = 0, employees = 4, steps = listOf(step("look", employees())))
            val waved = base.copy(settings = base.settings.copy(waveSize = 2))

            f.runner(settings = settings(proxies)).run(waved).outcome shouldBe RunOutcome.PASSED

            f.browser.opened
                .map { it.label to it.proxy?.server }
                .sortedBy { it.first } shouldContainExactly
                listOf(
                    "a01" to "http://10.0.0.1:3128",
                    "a02" to "http://10.0.0.2:3128",
                    "a03" to "http://10.0.0.2:3128",
                    "a04" to "http://10.0.0.3:3128",
                    "a05" to "http://10.0.0.3:3128",
                )
        }

    @Test
    fun `the admin in every wave counts as live when proxies are checked`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val proxies = (1..2).map { BrowserProxy("http://10.0.0.$it:3128") }
            val base = campaign(managers = 0, employees = 4, steps = listOf(step("look", employees())))

            val summary = f.runner(settings = settings(proxies)).run(base.copy(settings = base.settings.copy(waveSize = 2)))

            summary.outcome shouldBe RunOutcome.ABORTED
            f.evidence.stepList
                .single { it.action == "abort" }
                .detail
                .orEmpty() shouldContain "3 testers are live at once and only 2 proxies are given"
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
