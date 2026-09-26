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

package az.petek.dashboard.domain

import az.petek.dashboard.domain.DashboardUpdate.AgentUpdated
import az.petek.dashboard.domain.DashboardUpdate.EventRecorded
import az.petek.dashboard.domain.DashboardUpdate.PlanReady
import az.petek.dashboard.domain.DashboardUpdate.ReceiptRecorded
import az.petek.dashboard.domain.DashboardUpdate.RunCreated
import az.petek.dashboard.domain.DashboardUpdate.RunEnded
import az.petek.dashboard.domain.DashboardUpdate.RunStarted
import az.petek.dashboard.domain.DashboardUpdate.StepRecorded
import az.petek.dashboard.domain.DashboardUpdate.TaskUpdated
import az.petek.dashboard.testing.Records.OTHER_RUN
import az.petek.dashboard.testing.Records.RUN
import az.petek.dashboard.testing.Records.T0
import az.petek.dashboard.testing.Records.a
import az.petek.dashboard.testing.Records.at
import az.petek.dashboard.testing.Records.event
import az.petek.dashboard.testing.Records.receipt
import az.petek.dashboard.testing.Records.runRecord
import az.petek.dashboard.testing.Records.status
import az.petek.dashboard.testing.Records.step
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class OrchestratorStateTest {
    private fun board(vararg updates: DashboardUpdate) = DashboardState.EMPTY.applyAll(updates.asList())

    private fun DashboardState.view() = orchestrator(at(60))

    private val plan =
        RunPlanView(
            runId = RUN,
            campaignName = "Elan axını",
            steps =
                listOf(
                    PlanStepView(
                        "register",
                        true,
                        "all",
                        listOf(a(1), a(2), a(3)),
                        "run",
                        "register_and_login",
                        null,
                        null,
                        false,
                        emptyList(),
                    ),
                    PlanStepView(
                        "announce",
                        false,
                        "a01",
                        listOf(a(1)),
                        "do",
                        "Elan yarat",
                        "announcement_created",
                        null,
                        false,
                        emptyList(),
                    ),
                    PlanStepView(
                        "receive",
                        false,
                        "role:employee",
                        listOf(a(2), a(3)),
                        "do",
                        "Elanı gör",
                        null,
                        "announcement_created",
                        false,
                        listOf("visible_text 'Yeni elan' within 10s"),
                    ),
                ),
        )

    private fun task(
        step: String,
        agent: Int,
        state: TaskState,
        detail: String? = null,
    ) = TaskStateView(RUN, step, a(agent), state, detail)

    @Test
    fun `a ready plan claims its run and every cell starts pending`() {
        val view = board(PlanReady(plan, at(0))).view()

        view.run.runId shouldBe RUN
        view.plan shouldBe plan
        view.tasks.shouldBeEmpty()
        view.taskCounts[TaskState.PENDING] shouldBe 6
        view.taskCounts.keys.toList() shouldContainExactly TaskState.entries
    }

    @Test
    fun `the latest report of a task wins and is stamped with the harness time`() {
        val view =
            board(
                PlanReady(plan, at(0)),
                TaskUpdated(task("register", 2, TaskState.RUNNING), at(1)),
                TaskUpdated(task("register", 2, TaskState.PASSED), at(3)),
                TaskUpdated(task("receive", 3, TaskState.WAITING_EVENT, "announcement_created"), at(4)),
            ).view()

        view.tasks.map { it.stepId to it.state } shouldContainExactly
            listOf("register" to TaskState.PASSED, "receive" to TaskState.WAITING_EVENT)
        view.tasks.first().updatedAt shouldBe T0.plusSeconds(3)
        view.tasks.last().detail shouldBe "announcement_created"
        view.taskCounts[TaskState.PASSED] shouldBe 1
        view.taskCounts[TaskState.WAITING_EVENT] shouldBe 1
        view.taskCounts[TaskState.PENDING] shouldBe 4
    }

    @Test
    fun `tasks are listed in plan order and then agent order`() {
        val view =
            board(
                PlanReady(plan, at(0)),
                TaskUpdated(task("receive", 3, TaskState.RUNNING), at(1)),
                TaskUpdated(task("register", 3, TaskState.PASSED), at(1)),
                TaskUpdated(task("receive", 2, TaskState.LOST_RACE), at(1)),
                TaskUpdated(task("register", 1, TaskState.PASSED), at(1)),
            ).view()

        view.tasks.map { "${it.stepId}/${it.agentId}" } shouldContainExactly
            listOf("register/a01", "register/a03", "receive/a02", "receive/a03")
    }

    @Test
    fun `a repeated identical task report changes nothing`() {
        val state = board(PlanReady(plan, at(0)), TaskUpdated(task("register", 2, TaskState.RUNNING).copy(updatedAt = T0), at(1)))

        state.apply(TaskUpdated(task("register", 2, TaskState.RUNNING).copy(updatedAt = T0), at(2))) shouldBeSameInstanceAs state
    }

    @Test
    fun `tasks of another run are ignored while the shown run goes on`() {
        val state = board(PlanReady(plan, at(0)))

        state.apply(TaskUpdated(TaskStateView(OTHER_RUN, "register", a(2), TaskState.FAILED), at(1))) shouldBeSameInstanceAs state
    }

    @Test
    fun `a new run clears the plan, the tasks and the events`() {
        val state =
            board(
                PlanReady(plan, at(0)),
                TaskUpdated(task("register", 2, TaskState.PASSED), at(1)),
                EventRecorded(event(1), at(2)),
                RunEnded(RUN, RunOutcome.PASSED, 10, null, at(3)),
                RunCreated(runRecord(runId = OTHER_RUN), at(4)),
            )

        val view = state.view()

        view.plan.shouldBeNull()
        view.tasks.shouldBeEmpty()
        view.events.shouldBeEmpty()
        view.run.runId shouldBe OTHER_RUN
    }

    @Test
    fun `the orchestrator version moves only when its screen changes`() {
        val start = board(PlanReady(plan, at(0)), RunStarted(RUN, listOf(status(1)), at(0)))

        val acted = start.apply(StepRecorded(step(1), at(1))).apply(AgentUpdated(status(1, AgentState.WORKING, "announce", "click"), at(2)))
        val tasked = acted.apply(TaskUpdated(task("announce", 1, TaskState.RUNNING), at(3)))
        val evented = tasked.apply(EventRecorded(event(1), at(4)))

        acted.version shouldBe start.version + 2
        acted.orchestratorVersion shouldBe start.orchestratorVersion
        tasked.orchestratorVersion shouldBe start.orchestratorVersion + 1
        evented.orchestratorVersion shouldBe start.orchestratorVersion + 2
    }

    @Test
    fun `an event collects its receipts in agent order with median and maximum latency`() {
        val announced = event(1, name = "announcement_created", objectId = "184")
        val view =
            board(
                PlanReady(plan, at(0)),
                EventRecorded(announced, at(1)),
                ReceiptRecorded(receipt(announced, 4, latencyMs = 1_900), at(2)),
                ReceiptRecorded(receipt(announced, 2, latencyMs = 700), at(2)),
                ReceiptRecorded(receipt(announced, 3, received = false), at(12)),
                ReceiptRecorded(receipt(announced, 5, latencyMs = 1_100), at(2)),
            ).view()

        val shown = view.events.single()
        shown.name shouldBe "announcement_created"
        shown.emitter shouldBe a(1)
        shown.objectId shouldBe "184"
        shown.receipts.map { it.receiver } shouldContainExactly listOf(a(2), a(3), a(4), a(5))
        shown.received shouldBe 3
        shown.missing shouldBe 1
        shown.medianLatencyMs shouldBe 1_100
        shown.maxLatencyMs shouldBe 1_900
    }

    @Test
    fun `the same event or receipt reported twice counts once`() {
        val announced = event(1)
        val once = board(EventRecorded(announced, at(1)), ReceiptRecorded(receipt(announced, 2), at(2)))

        once.apply(EventRecorded(announced, at(3))) shouldBeSameInstanceAs once
        once.apply(ReceiptRecorded(receipt(announced, 2), at(4))) shouldBeSameInstanceAs once
        once.snapshot(at(5)).counters.events shouldBe 1
        once.snapshot(at(5)).counters.receiptsReceived shouldBe 1
    }

    @Test
    fun `the event list keeps the newest events`() {
        val updates = (1..(DashboardState.EVENTS_LIMIT + 3)).map { EventRecorded(event(1, name = "event_$it"), at(it.toLong())) }

        val view = board(*updates.toTypedArray()).view()

        view.events shouldHaveSize DashboardState.EVENTS_LIMIT
        view.events.first().name shouldBe "event_${DashboardState.EVENTS_LIMIT + 3}"
    }

    @Test
    fun `the matrix labels agents with their names and roles`() {
        val view = board(RunStarted(RUN, listOf(status(2, name = "Əli Kərimov"), status(1, name = "Aysel")), at(0))).view()

        view.agents.map { it.displayName } shouldContainExactly listOf("Aysel", "Əli Kərimov")
    }

    @Test
    fun `a plan without a run id applies to the shown run`() {
        val view = board(RunCreated(runRecord(), at(0)), PlanReady(plan.copy(runId = null), at(1))).view()

        view.plan!!.runId.shouldBeNull()
        view.run.runId shouldBe RUN
    }
}
