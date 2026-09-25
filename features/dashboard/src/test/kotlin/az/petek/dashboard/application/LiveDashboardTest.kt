/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.application

import az.petek.core.model.Role
import az.petek.core.testing.FakeHarnessClock
import az.petek.core.time.SystemHarnessClock
import az.petek.dashboard.domain.DashboardSnapshot
import az.petek.dashboard.domain.OrchestratorSnapshot
import az.petek.dashboard.domain.PlanStepView
import az.petek.dashboard.domain.RunPhase
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.TaskState
import az.petek.dashboard.domain.TaskStateView
import az.petek.dashboard.testing.FailingClock
import az.petek.dashboard.testing.Records.OTHER_RUN
import az.petek.dashboard.testing.Records.RUN
import az.petek.dashboard.testing.Records.a
import az.petek.dashboard.testing.Records.artifact
import az.petek.dashboard.testing.Records.assertion
import az.petek.dashboard.testing.Records.event
import az.petek.dashboard.testing.Records.identity
import az.petek.dashboard.testing.Records.receipt
import az.petek.dashboard.testing.Records.runRecord
import az.petek.dashboard.testing.Records.status
import az.petek.dashboard.testing.Records.step
import az.petek.dashboard.testing.SchedulerClock
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDashboardTest {
    @Test
    fun `monitor notifications become one live board`() {
        val clock = FakeHarnessClock()
        val dashboard = LiveDashboard(clock)

        dashboard.runStarted(RUN, listOf(status(1, name = "Aysel Məmmədova", role = Role.ADMIN), status(2)))
        dashboard.stepStarted("announce")
        dashboard.agentUpdated(status(1, AgentState.WORKING, "announce", "do: create the announcement", name = "Aysel Məmmədova"))
        dashboard.message("a02 is waiting for announcement_created")
        clock.advance(42.seconds)

        val view = dashboard.snapshot()

        dashboard.currentRunId shouldBe RUN
        view.run.phase shouldBe RunPhase.RUNNING
        view.run.currentScenarioStep shouldBe "announce"
        view.run.elapsedMs shouldBe 42_000
        view.agents.first().state shouldBe AgentState.WORKING
        view.agents.first().lastAction shouldBe "do: create the announcement"
        view.timeline.map { it.agentId } shouldBe listOf(a(2), null)
    }

    @Test
    fun `the end of the run shows its outcome and report directory`() {
        val dashboard = LiveDashboard(FakeHarnessClock())
        dashboard.runStarted(RUN, listOf(status(1)))

        dashboard.runFinished(RunSummary(RUN, RunOutcome.PASSED, 3, 0, 0, 0, "/tmp/evidence/run_1/report", 61_000))

        val view = dashboard.snapshot()
        view.run.phase shouldBe RunPhase.FINISHED
        view.run.outcome shouldBe RunOutcome.PASSED
        view.run.elapsedMs shouldBe 61_000
        dashboard.reportPath shouldBe "/tmp/evidence/run_1/report"
    }

    @Test
    fun `a thousand updates from fifty coroutines leave consistent counters`() =
        runBlocking<Unit> {
            val dashboard = LiveDashboard(SystemHarnessClock())
            val recorder = DashboardEvidenceRecorder(InMemoryEvidence(), dashboard)
            val agents = 2..51
            dashboard.runStarted(RUN, agents.map { status(it) })
            val startVersion = dashboard.snapshot().version

            withContext(Dispatchers.Default) {
                agents
                    .map { agent ->
                        launch {
                            repeat(UPDATES_PER_AGENT) { i ->
                                when (i % 4) {
                                    0 -> {
                                        recorder.step(step(agent, action = "step $i"))
                                    }

                                    1 -> {
                                        recorder.step(step(agent, action = "step $i", status = StepStatus.FAILED))
                                    }

                                    2 -> {
                                        recorder.assertion(assertion(agent))
                                    }

                                    else -> {
                                        val state = if (i == UPDATES_PER_AGENT - 1) AgentState.DONE else AgentState.WORKING
                                        dashboard.agentUpdated(status(agent, state, "tickets", "status $i"))
                                    }
                                }
                            }
                        }
                    }.joinAll()
            }

            val view = dashboard.snapshot()
            val perAgent = UPDATES_PER_AGENT / 4
            view.version shouldBe startVersion + agents.count() * UPDATES_PER_AGENT
            view.counters.stepsPassed shouldBe agents.count() * perAgent
            view.counters.stepsFailed shouldBe agents.count() * perAgent
            view.counters.assertionsPassed shouldBe agents.count() * perAgent
            view.counters.agentsByState[AgentState.DONE] shouldBe agents.count()
            view.agents.forEach {
                it.actionsDone shouldBe 2 * perAgent
                it.failures shouldBe perAgent
                it.lastAction shouldBe "status ${UPDATES_PER_AGENT - 1}"
            }
            val logged = agents.count() * perAgent * 3L
            view.timeline.first().seq shouldBe logged
            view.timeline.map { it.seq } shouldBe (logged downTo logged - view.timeline.size + 1).toList()
        }

    @Test
    fun `the stream sends the current board at once and then at most four boards per second`() =
        runTest {
            val dashboard = LiveDashboard(SchedulerClock(testScheduler))
            val seen = mutableListOf<DashboardSnapshot>()
            backgroundScope.launch { dashboard.updates.collect { seen += it } }
            runCurrent()

            seen shouldHaveSize 1
            seen.single().version shouldBe 0

            repeat(100) {
                dashboard.message("message $it")
                advanceTimeBy(10.milliseconds)
            }
            runCurrent()

            seen shouldHaveSize 5
            seen.zipWithNext().forEach { (earlier, later) ->
                Duration.between(earlier.generatedAt, later.generatedAt).toMillis() shouldBeGreaterThanOrEqualTo 250L
            }
            seen.last().version shouldBe 100
        }

    @Test
    fun `the stream always ends on the latest board and stays quiet while nothing changes`() =
        runTest {
            val dashboard = LiveDashboard(SchedulerClock(testScheduler))
            val seen = mutableListOf<DashboardSnapshot>()
            backgroundScope.launch { dashboard.updates.collect { seen += it } }
            runCurrent()

            dashboard.message("first")
            dashboard.message("second")
            advanceTimeBy(50.milliseconds)
            dashboard.message("third")
            advanceTimeBy(10.seconds)
            runCurrent()

            seen.map { it.version } shouldBe listOf(0L, 3L)
            seen
                .last()
                .timeline
                .first()
                .text shouldBe "third"
        }

    @Test
    fun `a new collector gets the current board at once`() =
        runTest {
            val dashboard = LiveDashboard(SchedulerClock(testScheduler))
            dashboard.runStarted(RUN, listOf(status(1)))
            val seen = mutableListOf<DashboardSnapshot>()

            backgroundScope.launch { dashboard.updates.collect { seen += it } }
            runCurrent()

            seen.single().run.runId shouldBe RUN
        }

    @Test
    fun `an update that cannot be applied is dropped and never reaches the caller`() {
        val clock = FailingClock(FakeHarnessClock())
        val dashboard = LiveDashboard(clock)
        dashboard.runStarted(RUN, listOf(status(2)))

        clock.failing = true
        dashboard.agentUpdated(status(2, AgentState.WORKING, "announce", "lost"))
        dashboard.message("lost too")
        dashboard.stepStarted("lost")
        dashboard.runFinished(RunSummary(RUN, RunOutcome.PASSED, 0, 0, 0, 0, null, 1))
        clock.failing = false
        dashboard.agentUpdated(status(2, AgentState.WAITING, "receive", "kept"))

        val view = dashboard.snapshot()
        view.agents.single().state shouldBe AgentState.WAITING
        view.agents.single().lastAction shouldBe "kept"
        view.run.phase shouldBe RunPhase.RUNNING
        view.timeline shouldHaveSize 0
    }

    @Test
    fun `the refresh interval must be positive`() {
        shouldThrow<IllegalArgumentException> { LiveDashboard(FakeHarnessClock(), refreshInterval = 0.milliseconds) }
    }

    @Test
    fun `artifacts are found only for the run on the board`() =
        runBlocking<Unit> {
            val dashboard = LiveDashboard(FakeHarnessClock())
            val recorder = DashboardEvidenceRecorder(InMemoryEvidence(), dashboard)
            dashboard.runStarted(RUN, listOf(status(2)))
            val shot = artifact("a02", file = "0001-screenshot.png")
            val foreign = artifact("a02", runId = OTHER_RUN, file = "0009-screenshot.png")

            recorder.artifact(shot)
            recorder.artifact(foreign)

            dashboard.artifact(shot.artifactId) shouldBe shot
            dashboard.artifactAt("a02/0001-screenshot.png") shouldBe shot
            dashboard.artifactAt("/a02/0001-screenshot.png") shouldBe shot
            dashboard.artifact(foreign.artifactId).shouldBeNull()
            dashboard.artifactAt("a02/0009-screenshot.png").shouldBeNull()
            dashboard.artifactAt("../run_1/a02/0001-screenshot.png").shouldBeNull()

            dashboard.runFinished(RunSummary(RUN, RunOutcome.PASSED, 0, 0, 0, 0, null, 1))
            dashboard.runStarted(OTHER_RUN, listOf(status(2)))
            val next = artifact("a02", runId = OTHER_RUN, file = "0001-screenshot.png")
            recorder.artifact(next)

            dashboard.artifact(shot.artifactId).shouldBeNull()
            dashboard.artifactAt("a02/0001-screenshot.png") shouldBe next
        }

    @Test
    fun `a replay puts a recorded run on the board and replaces the live one`() =
        runBlocking<Unit> {
            val dashboard = LiveDashboard(FakeHarnessClock())
            dashboard.runStarted(OTHER_RUN, listOf(status(5)))
            val liveVersion = dashboard.snapshot().version
            val evidence = InMemoryEvidence()
            val start = Instant.parse("2026-01-01T09:00:00Z")
            val run = runRecord(startedAt = start, endedAt = start.plusSeconds(30), result = RunResult.PASSED)
            evidence.create(run)
            val shot = artifact("a02")
            evidence.step(step(2, startedAt = start.plusSeconds(1), stepId = shot.stepId))
            evidence.artifact(shot)

            val view = dashboard.replay(run, evidence, listOf(identity(1), identity(2)), reportPath = "/tmp/report")

            view.version shouldBe dashboard.snapshot().version
            (view.version > liveVersion) shouldBe true
            view.run.runId shouldBe RUN
            view.run.phase shouldBe RunPhase.FINISHED
            view.run.elapsedMs shouldBe 30_000
            view.agents.map { it.agentId } shouldBe listOf(a(1), a(2))
            dashboard.artifact(shot.artifactId).shouldNotBeNull()
            dashboard.reportPath shouldBe "/tmp/report"
        }

    @Test
    fun `plan, task and event reports reach the orchestrator view and duplicates count once`() =
        runBlocking<Unit> {
            val dashboard = LiveDashboard(FakeHarnessClock())
            val recorder = DashboardEvidenceRecorder(InMemoryEvidence(), dashboard)
            val announced = event(1)
            dashboard.planReady(RunPlanView(RUN, "Elan axını", listOf(announceStep)))

            dashboard.taskUpdated(TaskStateView(RUN, "announce", a(1), TaskState.RUNNING))
            recorder.event(announced)
            dashboard.eventPublished(announced)
            dashboard.eventReceived(receipt(announced, 2))
            recorder.receipt(receipt(announced, 2))

            val view = dashboard.orchestrator()
            view.plan!!
                .steps
                .single()
                .id shouldBe "announce"
            view.tasks.single().state shouldBe TaskState.RUNNING
            view.events.single().receipts shouldHaveSize 1
            dashboard.snapshot().counters.events shouldBe 1
            dashboard.snapshot().counters.receiptsReceived shouldBe 1
        }

    @Test
    fun `the orchestrator stream moves only when its screen changes`() =
        runTest {
            val dashboard = LiveDashboard(SchedulerClock(testScheduler))
            dashboard.planReady(RunPlanView(RUN, "Elan axını", listOf(announceStep)))
            val seen = mutableListOf<OrchestratorSnapshot>()
            backgroundScope.launch { dashboard.orchestratorUpdates.collect { seen += it } }
            runCurrent()

            dashboard.runStarted(RUN, listOf(status(1)))
            dashboard.agentUpdated(status(1, AgentState.WORKING, "announce", "click [12]"))
            advanceTimeBy(1.seconds)
            dashboard.taskUpdated(TaskStateView(RUN, "announce", a(1), TaskState.PASSED))
            advanceTimeBy(1.seconds)
            runCurrent()

            seen shouldHaveSize 2
            seen.last().taskCounts[TaskState.PASSED] shouldBe 1
        }

    private val announceStep =
        PlanStepView("announce", false, "a01", listOf(a(1)), "do", "Elan yarat", "announcement_created", null, false, emptyList())

    private companion object {
        const val UPDATES_PER_AGENT = 20
    }
}
