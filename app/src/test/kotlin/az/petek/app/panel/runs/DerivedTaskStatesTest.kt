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

package az.petek.app.panel.runs

import az.petek.core.ids.AgentId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.testing.FakeHarnessClock
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.domain.PlanStepView
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.TaskState
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class DerivedTaskStatesTest {
    private val clock = FakeHarnessClock()
    private val dashboard = LiveDashboard(clock)
    private val tasks = DerivedTaskStates(dashboard)
    private val run = RunId("run_1")
    private val a01 = AgentId("a01")
    private val a02 = AgentId("a02")
    private val a03 = AgentId("a03")

    private val plan =
        RunPlanView(
            runId = run,
            campaignName = "kadrohr",
            steps =
                listOf(
                    step("announce", listOf(a01), emits = "announcement_created"),
                    step("read", listOf(a02, a03), waitFor = "announcement_created"),
                    step("race", listOf(a02, a03), parallel = true, assertions = listOf("only_one_succeeds")),
                ),
        )

    private fun step(
        id: String,
        agents: List<AgentId>,
        emits: String? = null,
        waitFor: String? = null,
        parallel: Boolean = false,
        assertions: List<String> = emptyList(),
    ) = PlanStepView(id, false, "admin", agents, "do", id, emits, waitFor, parallel, assertions)

    private fun status(
        agent: AgentId,
        state: AgentState,
        step: String?,
        action: String? = null,
    ) = AgentStatus(agent, agent.value, "employee", state, step, action, clock.now())

    private fun cell(
        step: String,
        agent: AgentId,
    ): TaskState? =
        dashboard
            .orchestrator()
            .tasks
            .firstOrNull { it.stepId == step && it.agentId == agent }
            ?.state

    private fun planned() {
        dashboard.planReady(plan)
        tasks.planned(plan)
        tasks.runStarted(run, emptyList())
    }

    @Test
    fun `an agent's status moves its cell from waiting to running to passed`() {
        planned()

        tasks.agentUpdated(status(a02, AgentState.WAITING, "read", "wait_for announcement_created"))
        cell("read", a02) shouldBe TaskState.WAITING_EVENT
        dashboard
            .orchestrator()
            .tasks
            .single()
            .detail shouldBe "wait_for announcement_created"

        tasks.agentUpdated(status(a02, AgentState.WORKING, "read", "Bildirişləri aç"))
        cell("read", a02) shouldBe TaskState.RUNNING

        tasks.agentUpdated(status(a02, AgentState.IDLE, "read", "ok: read the announcement"))
        cell("read", a02) shouldBe TaskState.PASSED
        dashboard.orchestrator().taskCounts[TaskState.PASSED] shouldBe 1
        dashboard.orchestrator().taskCounts[TaskState.PENDING] shouldBe 4
    }

    @Test
    fun `a failure key, a block and a failed setup are shown with their reason`() {
        planned()

        tasks.agentUpdated(status(a02, AgentState.IDLE, "read", "not_received announcement_created"))
        tasks.agentUpdated(status(a03, AgentState.BLOCKED, "read", "no progress for 120 s"))
        tasks.agentUpdated(status(a01, AgentState.FAILED, "announce", "failed setup: mail_unavailable"))

        cell("read", a02) shouldBe TaskState.FAILED
        cell("read", a03) shouldBe TaskState.BLOCKED
        cell("announce", a01) shouldBe TaskState.FAILED
        dashboard
            .orchestrator()
            .tasks
            .first { it.agentId == a01 }
            .detail shouldBe "failed setup: mail_unavailable"
    }

    @Test
    fun `statuses without a step, the end of the run and steps outside the plan change nothing`() {
        planned()

        tasks.agentUpdated(status(a02, AgentState.IDLE, null, "ok: x"))
        tasks.agentUpdated(status(a02, AgentState.DONE, "read", "ok: x"))
        tasks.agentUpdated(status(a02, AgentState.IDLE, "read", null))
        tasks.agentUpdated(status(a02, AgentState.WORKING, "not-in-plan", "x"))

        dashboard.orchestrator().tasks.shouldBeEmpty()
    }

    @Test
    fun `skipped records and a passed race check are shown through the recorder`() =
        runTest {
            planned()
            val recorder = tasks.recorder(InMemoryEvidence())

            recorder.step(record(a03, "announce", StepKind.SYSTEM, StepStatus.SKIPPED, "agent failed earlier (mail_unavailable)"))
            tasks.agentUpdated(status(a02, AgentState.IDLE, "race", "ok: approved"))
            tasks.agentUpdated(status(a03, AgentState.IDLE, "race", "action_failed: already decided"))
            recorder.assertion(groupCheck("race", Verdict.PASSED))

            cell("announce", a03) shouldBe TaskState.SKIPPED
            cell("race", a02) shouldBe TaskState.PASSED
            cell("race", a03) shouldBe TaskState.LOST_RACE
        }

    @Test
    fun `a failed race check leaves the failed actors failed`() =
        runTest {
            planned()
            val recorder = tasks.recorder(InMemoryEvidence())
            tasks.agentUpdated(status(a02, AgentState.IDLE, "race", "action_failed: x"))
            tasks.agentUpdated(status(a03, AgentState.IDLE, "race", "action_failed: y"))

            recorder.assertion(groupCheck("race", Verdict.FAILED))

            cell("race", a02) shouldBe TaskState.FAILED
            cell("race", a03) shouldBe TaskState.FAILED
        }

    @Test
    fun `records of another run and statuses before any run are ignored`() =
        runTest {
            tasks.agentUpdated(status(a02, AgentState.WORKING, "read", "x"))
            planned()
            tasks.recorder(InMemoryEvidence()).step(record(a02, "read", StepKind.SYSTEM, StepStatus.SKIPPED, "x", RunId("run_other")))

            dashboard.orchestrator().tasks.shouldBeEmpty()
        }

    @Test
    fun `a new run starts an empty matrix`() {
        planned()
        tasks.agentUpdated(status(a02, AgentState.WORKING, "read", "x"))

        dashboard.runFinished(RunSummary(run, RunOutcome.PASSED, 1, 0, 0, 0, null, 1_000))
        tasks.runStarted(RunId("run_2"), emptyList())
        tasks.agentUpdated(status(a02, AgentState.WORKING, "read", "x"))

        dashboard
            .orchestrator()
            .tasks
            .map { it.runId }
            .distinct() shouldBe listOf(RunId("run_2"))
    }

    private fun record(
        agent: AgentId,
        step: String,
        kind: StepKind,
        status: StepStatus,
        detail: String,
        runId: RunId = run,
    ) = StepRecord(StepId("stp_1"), runId, agent, step, kind, "skip", null, T, T, 0, status, detail, CorrelationId("cor_1"))

    private fun groupCheck(
        step: String,
        verdict: Verdict,
    ) = AssertionRecord(
        StepId("stp_2"),
        run,
        null,
        step,
        "only_one_succeeds",
        EvidenceSource.HARNESS,
        "1",
        "1",
        verdict,
        null,
        null,
        emptyList(),
    )

    private companion object {
        val T: Instant = Instant.parse("2026-01-01T10:00:00Z")
    }
}
