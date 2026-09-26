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
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.core.ids.AgentId
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.PlannedActionKind
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunPlan
import az.petek.orchestration.domain.TaskState
import az.petek.orchestration.domain.TaskState.FAILED
import az.petek.orchestration.domain.TaskState.LOST_RACE
import az.petek.orchestration.domain.TaskState.PASSED
import az.petek.orchestration.domain.TaskState.PENDING
import az.petek.orchestration.domain.TaskState.RUNNING
import az.petek.orchestration.domain.TaskState.SKIPPED
import az.petek.orchestration.domain.TaskState.WAITING_EVENT
import az.petek.orchestration.domain.TaskUpdate
import az.petek.orchestration.testing.RecordingMonitor
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.everyoneButAdmin
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The orchestrator's task plan, task transitions and event timeline as a live view receives them. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerTasksTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    private val ok = ActionOutcome(ActionStatus.SUCCEEDED, "ok")

    /**
     * a01 admin, a02 manager IT, a03 manager HR, a04 employee IT, a05 employee HR: everyone joins, a04 writes a
     * ticket, a05 waits for it and reads it, the two managers race to approve it.
     */
    private fun small() =
        campaign(
            setup = listOf(setupStep("join", everyoneButAdmin())),
            steps =
                listOf(
                    step("ticket", employees("IT", nth = 1), StepAction.Do("Write a ticket"), emits = "ticket_created"),
                    step(
                        "read",
                        employees("HR"),
                        waitFor = "ticket_created",
                        assertions = listOf(AssertionSpec.VisibleText("Noutbuk", 5.seconds)),
                    ),
                    step(
                        "race",
                        managers(),
                        StepAction.Do("Approve {last_id}"),
                        parallel = true,
                        assertions = listOf(AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", ".*/approve"))),
                    ),
                ),
            managers = 2,
            employees = 2,
        )

    /** a04's ticket is t1, a05's page shows it, a02 wins the approval (303) and a03 is refused (409). */
    private fun RunnerFixture.scriptSmallRun() {
        browser.configure = { if (it.label == "a05") it.visibleTexts += "Noutbuk" }
        agents.script = { call, _ ->
            if (call.scenarioStep == "race") {
                val status = if (call.agentId == AgentId("a02")) 303 else 409
                browser.session(call.agentId.value).mutated("POST", "/tickets/t1/approve", status)
            }
            if (call.scenarioStep == "ticket") ActionOutcome(ActionStatus.SUCCEEDED, "ok", objectId = "t1") else ok
        }
    }

    @Test
    fun `a view gets the plan first and then every task from pending to its final state`() =
        runTest {
            val f = fixture()
            f.scriptSmallRun()

            val summary = f.runner().run(small())

            summary.outcome shouldBe RunOutcome.PASSED
            val plan = f.monitor.plans.single()
            plan.runId shouldBe summary.runId
            plan.steps.map { it.id to it.resolvedAgents.map(AgentId::value) } shouldContainExactly
                listOf(
                    "join" to listOf("a02", "a03", "a04", "a05"),
                    "ticket" to listOf("a04"),
                    "read" to listOf("a05"),
                    "race" to listOf("a02", "a03"),
                )
            val race = plan.steps.last()
            race.phase shouldBe StepPhase.MAIN
            race.actionKind shouldBe PlannedActionKind.DO
            race.actionText shouldBe "Approve {last_id}"
            race.parallel shouldBe true
            race.assertionTypes shouldContainExactly listOf("only_one_succeeds")
            plan.steps[0].let { join ->
                join.phase shouldBe StepPhase.SETUP
                join.actionKind shouldBe PlannedActionKind.RUN
                join.actionText shouldBe "register_and_login"
            }
            plan.steps[1].emits shouldBe "ticket_created"
            plan.steps[2].waitFor shouldBe "ticket_created"

            f.monitor.statesOf("join", "a03") shouldContainExactly listOf(PENDING, RUNNING, PASSED)
            f.monitor.statesOf("ticket", "a04") shouldContainExactly listOf(PENDING, RUNNING, PASSED)
            f.monitor.statesOf("read", "a05") shouldContainExactly listOf(PENDING, WAITING_EVENT, RUNNING, PASSED)
            f.monitor.statesOf("race", "a02") shouldContainExactly listOf(PENDING, RUNNING, PASSED)
            f.monitor.statesOf("race", "a03") shouldContainExactly listOf(PENDING, RUNNING, LOST_RACE)
            // 8 tasks start PENDING, then join 4 x 2 moves, ticket 2, read 3 and race 2 x 2.
            f.monitor.tasks shouldHaveSize 8 + 8 + 2 + 3 + 4
        }

    @Test
    fun `the timeline orders plan, steps, events and task moves as they happened`() =
        runTest {
            val f = fixture()
            f.scriptSmallRun()

            f.runner().run(small())

            val timeline = f.monitor.timeline

            fun at(line: String): Int = timeline.indexOf(line).also { check(it >= 0) { "'$line' not in $timeline" } }

            at("runStarted 5") shouldBeLessThan at("plan join=a02,a03,a04,a05 ticket=a04 read=a05 race=a02,a03")
            at("plan join=a02,a03,a04,a05 ticket=a04 read=a05 race=a02,a03") shouldBeLessThan at("task join a02 PENDING")
            at("task race a03 PENDING") shouldBeLessThan at("step join")
            at("step ticket") shouldBeLessThan at("task ticket a04 RUNNING")
            at("task ticket a04 RUNNING") shouldBeLessThan at("published ticket_created by a04")
            at("published ticket_created by a04") shouldBeLessThan at("task ticket a04 PASSED")
            at("task read a05 WAITING_EVENT") shouldBeLessThan at("received ticket_created by a05")
            at("received ticket_created by a05") shouldBeLessThan at("task read a05 RUNNING")
            at("step race") shouldBeLessThan at("task race a02 RUNNING")
            at("task race a03 LOST_RACE") shouldBeLessThan at("runFinished PASSED")
            f.monitor.published
                .single()
                .emitter shouldBe AgentId("a04")
            f.monitor.received shouldContainExactly listOf("received ticket_created by a05")
        }

    @Test
    fun `task details say what the agent waits for, does and how it ended`() =
        runTest {
            val f = fixture()
            f.scriptSmallRun()

            f.runner().run(small())

            fun detail(
                step: String,
                agent: String,
                state: TaskState,
            ): String? =
                f.monitor.tasks
                    .single { it.stepId == step && it.agentId == AgentId(agent) && it.state == state }
                    .detail

            detail("read", "a05", WAITING_EVENT) shouldBe "wait_for ticket_created"
            detail("race", "a02", RUNNING) shouldBe "do: Approve t1"
            detail("race", "a02", PASSED) shouldBe "ok: ok"
            detail("race", "a03", LOST_RACE) shouldBe "lost_race: POST /tickets/t1/approve -> 409; won by a02; agent: ok"
            detail("race", "a03", PENDING) shouldBe null
        }

    @Test
    fun `task updates carry the harness time of the transition`() =
        runTest {
            val f = fixture()
            f.scriptSmallRun()
            val script = f.agents.script
            f.agents.script = { call, runtime ->
                if (call.scenarioStep == "ticket") delay(7.seconds)
                script(call, runtime)
            }

            f.runner().run(small())

            val ticket = f.monitor.tasks.filter { it.stepId == "ticket" }
            ticket.map { it.state } shouldContainExactly listOf(PENDING, RUNNING, PASSED)
            ticket[2].at.monotonicNanos - ticket[1].at.monotonicNanos shouldBe 7.seconds.inWholeNanoseconds
        }

    @Test
    fun `a tester that fails setup leaves the plan, which is sent again, and its later tasks are skipped`() =
        runTest {
            val f = fixture()
            f.scriptSmallRun()
            val script = f.agents.script
            f.agents.script = { call, runtime ->
                if (call.scenarioStep == "join" && call.agentId == AgentId("a03")) {
                    ActionOutcome(ActionStatus.FAILED, "no e-mail", failureReason = FailureReason.MAIL_TIMEOUT)
                } else {
                    script(call, runtime)
                }
            }

            f.runner().run(small())

            f.monitor.plans shouldHaveSize 2
            val (before, after) = f.monitor.plans
            before.steps
                .last()
                .resolvedAgents
                .map(AgentId::value) shouldContainExactly listOf("a02", "a03")
            after.steps
                .last()
                .resolvedAgents
                .map(AgentId::value) shouldContainExactly listOf("a02")
            // A step that already ran keeps the agents that ran it.
            after.steps
                .first()
                .resolvedAgents
                .map(AgentId::value) shouldContainExactly listOf("a02", "a03", "a04", "a05")
            f.monitor.statesOf("join", "a03") shouldContainExactly listOf(PENDING, RUNNING, FAILED)
            f.monitor.statesOf("race", "a03") shouldContainExactly listOf(PENDING, SKIPPED)
            f.monitor.tasks
                .last { it.stepId == "race" && it.agentId == AgentId("a03") }
                .detail shouldBe "agent failed earlier (mail_timeout)"
        }

    @Test
    fun `an aborted run ends every task it never reached as skipped`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep ==
                    "owner"
                ) {
                    ActionOutcome(ActionStatus.FAILED, "sign-up broken", failureReason = FailureReason.REGISTRATION_FAILED)
                } else {
                    ok
                }
            }
            val campaign =
                campaign(
                    setup = listOf(setupStep("owner", admin(), StepAction.Run("register_owner")), setupStep("join", employees())),
                    steps = listOf(step("work", employees())),
                )

            val summary = f.runner().run(campaign)

            summary.outcome shouldBe RunOutcome.ABORTED
            f.monitor.statesOf("owner", "a01") shouldContainExactly listOf(PENDING, RUNNING, FAILED)
            f.monitor.statesOf("join", "a04") shouldContainExactly listOf(PENDING, SKIPPED)
            f.monitor.statesOf("work", "a07") shouldContainExactly listOf(PENDING, SKIPPED)
            val skipped = f.monitor.tasks.filter { it.state == SKIPPED }
            skipped.map { it.stepId }.distinct() shouldContainExactly listOf("join", "work")
            skipped.forEach { it.detail!! shouldStartWith "run aborted: the admin failed setup step 'owner'" }
            f.monitor.timeline.indexOf("task work a07 SKIPPED") shouldBeLessThan f.monitor.timeline.indexOf("runFinished ABORTED")
        }

    @Test
    fun `a receiver that does not see the event fails its task and the event is reported missed`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ -> ok }

            f.runner().run(small())

            f.monitor.received shouldContainExactly listOf("missed ticket_created by a05")
            f.monitor.statesOf("read", "a05") shouldContainExactly listOf(PENDING, WAITING_EVENT, RUNNING, FAILED)
        }

    @Test
    fun `a receiver whose event never comes fails its task`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "ticket") ActionOutcome(ActionStatus.FAILED, "could not write") else ok
            }

            f.runner().run(small())

            f.monitor.published.shouldBeEmpty()
            f.monitor.statesOf("read", "a05") shouldContainExactly listOf(PENDING, WAITING_EVENT, FAILED)
            f.monitor.tasks
                .last { it.stepId == "read" }
                .detail!! shouldStartWith "not_received: ticket_created"
        }

    @Test
    fun `a refusal a forbidden-action step expects passes its task and says it was refused`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ ->
                ActionOutcome(ActionStatus.BLOCKED, "no approve button", failureReason = FailureReason.PERMISSION_DENIED)
            }

            f.runner().run(campaign(steps = listOf(step("forbidden", employees("IT", nth = 2)))))

            f.monitor.statesOf("forbidden", "a06") shouldContainExactly listOf(PENDING, RUNNING, PASSED)
            f.monitor.tasks
                .last { it.stepId == "forbidden" }
                .detail shouldBe "permission_denied: no approve button"
        }

    @Test
    fun `a view that throws never breaks the run`() =
        runTest {
            val f = fixture()
            f.scriptSmallRun()
            val broken =
                object : MonitorView by RecordingMonitor() {
                    override fun planReady(plan: RunPlan): Unit = error("panel closed")

                    override fun taskUpdated(update: TaskUpdate): Unit = error("panel closed")
                }

            val summary = f.runner(monitor = broken).run(small())

            summary.outcome shouldBe RunOutcome.PASSED
            summary.stepsFailed shouldBe 0
        }
}
