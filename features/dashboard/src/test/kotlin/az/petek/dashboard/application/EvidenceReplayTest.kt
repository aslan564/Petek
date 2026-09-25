/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.application

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.dashboard.domain.RunPhase
import az.petek.dashboard.domain.TimelineKind
import az.petek.dashboard.testing.Records.a
import az.petek.dashboard.testing.Records.artifact
import az.petek.dashboard.testing.Records.assertion
import az.petek.dashboard.testing.Records.event
import az.petek.dashboard.testing.Records.finding
import az.petek.dashboard.testing.Records.identity
import az.petek.dashboard.testing.Records.receipt
import az.petek.dashboard.testing.Records.runRecord
import az.petek.dashboard.testing.Records.step
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

class EvidenceReplayTest {
    private val start = Instant.parse("2026-01-01T10:00:00Z")
    private val evidence = InMemoryEvidence()
    private val identities =
        listOf(
            identity(1, name = "Aysel Məmmədova"),
            identity(2, name = "Əli Kərimov", role = Role.MANAGER, department = "Satış"),
            identity(3, name = "Günel Həsənova", status = IdentityStatus.FAILED),
        )

    private suspend fun recordRun() {
        val announce = step(1, action = "click [21] \"Dərc et\"", startedAt = start.plusSeconds(10), scenarioStep = "announce")
        evidence.step(
            step(1, action = "run register_and_login", kind = StepKind.RUN, startedAt = start.plusSeconds(1), scenarioStep = "register"),
        )
        evidence.step(announce)
        evidence.step(
            step(
                3,
                action = "run register_and_login",
                kind = StepKind.RUN,
                status = StepStatus.FAILED,
                detail = "verification_code_missing",
                startedAt = start.plusSeconds(2),
                scenarioStep = "register",
            ),
        )
        evidence.artifact(artifact("a01", stepId = announce.stepId, file = "0002-screenshot.png"))
        evidence.artifact(artifact("a01", ArtifactType.A11Y, stepId = announce.stepId))
        val announced = event(1, t0 = start.plusSeconds(11))
        evidence.event(announced)
        evidence.receipt(receipt(announced, 2, latencyMs = 900))
        evidence.assertion(assertion(2, Verdict.PASSED, stepId = announce.stepId))
        evidence.assertion(assertion(3, Verdict.SKIPPED, note = "agent excluded"))
        evidence.finding(finding(3, note = "a03 could not register"))
    }

    @Test
    fun `a finished run is rebuilt from the evidence store`() =
        runBlocking<Unit> {
            recordRun()
            val run = runRecord(startedAt = start, endedAt = start.plusSeconds(95), result = RunResult.FAILED)

            val view = snapshotFromEvidence(run, evidence, identities, reportPath = "/tmp/evidence/run_1/report")

            view.run.runId shouldBe run.runId
            view.run.campaignName shouldBe run.campaignName
            view.run.phase shouldBe RunPhase.FINISHED
            view.run.outcome shouldBe RunOutcome.FAILED
            view.run.elapsedMs shouldBe 95_000
            view.run.reportPath shouldBe "/tmp/evidence/run_1/report"
            view.agents.map { it.state } shouldContainExactly listOf(AgentState.DONE, AgentState.DONE, AgentState.FAILED)
            view.agents[0].lastAction shouldBe "click [21] \"Dərc et\""
            view.agents[0].scenarioStep shouldBe "announce"
            view.agents[0].lastScreenshotArtifactId shouldBe evidence.artifactList.first().artifactId
            view.agents[0].registration shouldBe RegistrationMode.OWNER
            view.agents[1].department shouldBe "Satış"
            view.agents[1].role shouldBe Role.MANAGER
            view.agents[2].failures shouldBe 1
            view.agents[2].lastFailureReason shouldBe "verification_code_missing"
            with(view.counters) {
                stepsPassed shouldBe 2
                stepsFailed shouldBe 1
                events shouldBe 1
                receiptsReceived shouldBe 1
                assertionsPassed shouldBe 1
                assertionsSkipped shouldBe 1
                findings shouldBe 1
            }
            view.timeline.first().text shouldBe "Run bitdi: uğursuz"
            view.timeline[1].text shouldBe "sıradan çıxdı — verification_code_missing"
            view.timeline[2].kind shouldBe TimelineKind.FINDING
            view.timeline.last().text shouldBe "run register_and_login"
            view.timeline.last().agentId shouldBe a(1)
            view.generatedAt shouldBe start.plusSeconds(95)
        }

    @Test
    fun `a run that never recorded its end is shown as interrupted with idle agents`() =
        runBlocking<Unit> {
            recordRun()
            val run = runRecord(startedAt = start, endedAt = null, result = RunResult.RUNNING)

            val view = snapshotFromEvidence(run, evidence, identities)

            view.run.phase shouldBe RunPhase.INTERRUPTED
            view.run.outcome.shouldBeNull()
            view.run.elapsedMs shouldBe 11_900
            view.agents.map { it.state } shouldContainExactly listOf(AgentState.IDLE, AgentState.IDLE, AgentState.FAILED)
        }

    @Test
    fun `a run without evidence shows its agents and header`() =
        runBlocking<Unit> {
            val run = runRecord(startedAt = start, endedAt = start.plusSeconds(3), result = RunResult.ABORTED)

            val view = snapshotFromEvidence(run, evidence, identities.take(1))

            view.run.outcome shouldBe RunOutcome.ABORTED
            view.agents shouldHaveSize 1
            view.agents.single().actionsDone shouldBe 0
            view.timeline.single().text shouldBe "Run dayandırıldı"
        }
}
