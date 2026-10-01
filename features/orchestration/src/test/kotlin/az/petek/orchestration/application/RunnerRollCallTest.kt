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
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.Pacing
import az.petek.core.model.Role
import az.petek.evidence.domain.ABORT_ACTION
import az.petek.evidence.domain.CAPACITY_ACTION
import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.ROSTER_ACTION
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UNCOVERED_ACTION
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.actors
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.everyoneButAdmin
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.selector
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Nobody planned is left out of the evidence: the roster at the start, a `not_reached` record for every planned
 * tester × step without a final record of its own at the end (waves included), the abort reason per wave, the machine's
 * capacity next to the run's size, and a step nobody ran counted against the run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerRollCallTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    private val ok = ActionOutcome(ActionStatus.SUCCEEDED, "ok")

    private fun RunnerFixture.rollCall(): List<Triple<String, String, String>> =
        system(NOT_REACHED_ACTION).map { Triple(it.scenarioStep, it.agentId!!.value, it.detail!!) }

    private fun Campaign.inWavesOf(size: Int): Campaign = copy(settings = settings.copy(waveSize = size))

    @Test
    fun `the roster lists every planned tester at the start and a run that went to its end needs no roll call`() =
        runTest {
            val f = fixture()

            val summary = f.runner().run(campaign(managers = 1, employees = 2, steps = listOf(step("work", everyoneButAdmin()))))

            f.system(ROSTER_ACTION).single().let {
                it.agentId shouldBe null
                it.status shouldBe StepStatus.PASSED
                it.detail shouldBe "4 testers: a01, a02, a03, a04"
            }
            f.evidence.stepList
                .first()
                .action shouldBe ROSTER_ACTION
            f.rollCall().shouldBeEmpty()
            summary.outcome shouldBe RunOutcome.PASSED
        }

    @Test
    fun `testers of a wave the budget cut short or never reached are each accounted for`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ ->
                delay(50.seconds)
                ok
            }
            // Six employees in three waves of two (a02 a05 | a03 a06 | a04 a07), two steps of 50 s, a budget of 3 min:
            // the budget runs out during wave 2's `check`, and wave 3 never begins.
            val waved =
                campaign(
                    managers = 0,
                    employees = 6,
                    steps = listOf(step("read", employees()), step("check", employees())),
                    maxMinutes = 3,
                ).inWavesOf(2)

            val summary = f.runner().run(waved)

            summary.outcome shouldBe RunOutcome.ABORTED
            val budget = "time budget of 3 min exceeded"
            f.rollCall() shouldContainExactly
                listOf(
                    Triple("check", "a03", "run_aborted: $budget"),
                    Triple("check", "a06", "run_aborted: $budget"),
                    Triple("read", "a04", "wave_not_started: wave 3 of 3 never began; run aborted: $budget"),
                    Triple("read", "a07", "wave_not_started: wave 3 of 3 never began; run aborted: $budget"),
                    Triple("check", "a04", "wave_not_started: wave 3 of 3 never began; run aborted: $budget"),
                    Triple("check", "a07", "wave_not_started: wave 3 of 3 never began; run aborted: $budget"),
                )
            f.system(NOT_REACHED_ACTION).map { it.status }.toSet() shouldBe setOf(StepStatus.SKIPPED)
            // Wave 2's `check` began; only wave 3's steps never did, although wave 1 ran them all.
            f.system(ABORT_ACTION).single().detail shouldBe "run aborted: $budget; steps not run: wave 3: read, check"
            // Every planned tester × step has a final record: its own action, or why it had none.
            val accounted =
                f.evidence.stepList
                    .filter { it.agentId != null && (it.kind == StepKind.DO || it.action == NOT_REACHED_ACTION) }
                    .map { it.scenarioStep to it.agentId!!.value }
            accounted.toSet() shouldBe listOf("read", "check").flatMap { s -> (2..7).map { s to "a0$it" } }.toSet()
        }

    @Test
    fun `after an abort the testers of the steps never reached are named, those out since setup with their failure`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                when {
                    call.scenarioStep == "join" && call.agentId.value == "a05" -> {
                        ActionOutcome(ActionStatus.FAILED, "no code", failureReason = FailureReason.REGISTRATION_FAILED)
                    }

                    call.scenarioStep == "announce" -> {
                        ActionOutcome(ActionStatus.FAILED, "the form was refused")
                    }

                    else -> {
                        ok
                    }
                }
            }
            val campaign =
                campaign(
                    setup = listOf(setupStep("join", everyoneButAdmin())),
                    steps = listOf(step("announce", admin(), onFail = OnFail.ABORT), step("read", employees())),
                )

            f.runner().run(campaign)

            val aborted = "run_aborted: step 'announce' failed and on_fail is abort"
            f.rollCall() shouldContainExactly
                listOf(
                    Triple("read", "a04", aborted),
                    Triple("read", "a05", "failed_earlier: registration_failed"),
                    Triple("read", "a06", aborted),
                    Triple("read", "a07", aborted),
                )
            f.system(ABORT_ACTION).single().detail shouldBe
                "run aborted: step 'announce' failed and on_fail is abort; steps not run: read"
        }

    @Test
    fun `actors still waiting for their turn when the budget ends are accounted for`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ ->
                delay(50.seconds)
                ok
            }
            val base = campaign(managers = 0, employees = 5, steps = listOf(step("read", employees())), maxMinutes = 2)
            val paced = base.copy(settings = base.settings.copy(pacing = Pacing(maxParallelActors = 1)))

            f.runner().run(paced)

            // a02 and a03 read in turn; a04 was cut off acting, a05 and a06 never got their turn.
            f.steps("read", StepKind.DO).map { it.agentId!!.value } shouldContainExactly listOf("a02", "a03")
            f.rollCall().map { it.second to it.third } shouldContainExactly
                listOf("a04", "a05", "a06").map { it to "run_aborted: time budget of 2 min exceeded" }
        }

    @Test
    fun `testers the account swap planned are accounted for when the budget ends it`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ ->
                delay(50.seconds)
                ok
            }
            // `read` takes 50 s in the first pass; the swap's `read@swap` is cut off by the 1 min budget.
            val campaign = campaign(managers = 0, employees = 2, steps = listOf(step("read", employees())), maxMinutes = 1)

            f.runner().run(campaign, RunOptions(swapAccounts = true))

            f.rollCall() shouldContainExactly
                listOf("a02", "a03").map { Triple("read@swap", it, "run_aborted: time budget of 1 min exceeded") }
        }

    @Test
    fun `the machine's capacity is recorded next to the run's size and changes no verdict`() =
        runTest {
            val within = fixture()
            within.runner().run(
                campaign(managers = 0, employees = 3, steps = listOf(step("read", employees()))),
                RunOptions(capacityAdvice = 4),
            )
            val over = fixture()

            val summary =
                over.runner().run(
                    campaign(managers = 0, employees = 4, steps = listOf(step("read", employees()))).inWavesOf(2),
                    RunOptions(capacityAdvice = 2),
                )

            within.system(CAPACITY_ACTION).single().let {
                it.status shouldBe StepStatus.PASSED
                it.detail shouldBe "within_capacity: 4 testers at once (4 in the run); this machine is advised for up to 4 at once"
            }
            // Waves keep three live at once (the admin and a wave of two): still one more than advised.
            over.system(CAPACITY_ACTION).single().let {
                it.status shouldBe StepStatus.SKIPPED
                it.detail shouldBe
                    "over_capacity: 3 testers at once (5 in the run); this machine is advised for up to 2 at once, so slow " +
                    "pages and late screens may come from this machine, not from the site"
            }
            summary.outcome shouldBe RunOutcome.PASSED
            fixture().apply { runner().run(campaign(steps = listOf(step("read", employees())))) }.system(CAPACITY_ACTION).shouldBeEmpty()
        }

    @Test
    fun `a step no wave has a tester for is done by nobody, so the run cannot pass`() =
        runTest {
            val f = fixture()
            // Two managers dealt one to each wave: 'the second manager' is nobody in every wave.
            val secondManager = actors(selector(Role.MANAGER, nth = 2))
            val waved =
                campaign(
                    managers = 2,
                    employees = 2,
                    steps =
                        listOf(
                            step("read", employees()),
                            step("approve", secondManager, assertions = listOf(AssertionSpec.Count("#approved", 1))),
                        ),
                ).inWavesOf(2)

            val summary = f.runner().run(waved)

            val uncovered = f.system(UNCOVERED_ACTION).single()
            uncovered.agentId shouldBe null
            uncovered.scenarioStep shouldBe "approve"
            uncovered.status shouldBe StepStatus.FAILED
            uncovered.detail shouldBe
                "not_covered: no tester matched '${secondManager.raw}' in any wave it ran in (1 of 2); nobody ran this step"
            f.evidence.assertionList
                .filter { it.scenarioStep == "approve" }
                .map { it.verdict to it.stepId } shouldContainExactly listOf(Verdict.SKIPPED to uncovered.stepId)
            f.steps("read", StepKind.DO).map { it.status }.toSet() shouldBe setOf(StepStatus.PASSED)
            summary.outcome shouldBe RunOutcome.FAILED
            summary.stepsFailed shouldBe 1
            summary.failedAgents shouldBe 0
        }

    @Test
    fun `a step nobody ran says which of its testers were out after failing earlier`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "join" && call.agentId.value == "a02") {
                    ActionOutcome(ActionStatus.FAILED, "no code", failureReason = FailureReason.REGISTRATION_FAILED)
                } else {
                    ok
                }
            }
            val campaign =
                campaign(managers = 1, setup = listOf(setupStep("join", everyoneButAdmin())), steps = listOf(step("approve", managers())))

            f.runner().run(campaign)

            f.system(UNCOVERED_ACTION).single().detail shouldBe
                "not_covered: no tester matched '${managers().raw}' in the run; nobody ran this step; its testers were out after " +
                "failing earlier: a02 (registration_failed)"
            // Its own skip says why it sat the step out, so the roll call has nothing to add.
            f.rollCall().shouldBeEmpty()
        }
}
