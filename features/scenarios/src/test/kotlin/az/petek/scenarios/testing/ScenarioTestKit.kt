/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.testing

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.scenarios.domain.ScenarioHash
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.infrastructure.CampaignScenarioValidator
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Path
import java.time.Instant

/**
 * Shared test data: a small valid campaign, the real campaign loader + validator (so proposals are checked exactly as
 * a run would check them), and builders for evidence records.
 */
object ScenarioTestKit {
    /** Run functions the agent feature provides (the ones the test scenarios use). */
    val RUN_FUNCTIONS: Set<String> = setOf("register_and_login", "seed_company", "login")

    /** A valid 4-tester campaign with setup, an announcement, a race and a forbidden-action step. */
    val MINI_YAML: String =
        """
        |# Mini campaign for scenario tests.
        |campaign:
        |  name: mini
        |  target: https://staging.kadrohr.test
        |  testers: 4
        |  seed: 7
        |  roles: {admin: 1, manager: 1, employee: 2}
        |  departments: [IT]
        |  registration: {invite: 2, company_code: 1}
        |  budget: {max_steps_per_agent: 30, max_minutes: 10}
        |
        |target_profile:
        |  id_sources:
        |    announcement_created:
        |      oracle: {path: "/test/announcements/latest?by={self.email}", field: id}
        |
        |setup:
        |  - id: join
        |    actor: employee[*] | manager[*]
        |    run: register_and_login
        |
        |steps:
        |  - id: announce
        |    actor: admin
        |    do: "Elan yarat: 'Sabah 10:00 ümumi iclas'"
        |    emits: announcement_created
        |
        |  - id: read_announce
        |    actor: employee[*]
        |    wait_for: announcement_created
        |    do: "Bildirişləri aç və yeni elanı oxu"
        |    assert:
        |      - visible_text: {text: "Sabah 10:00 ümumi iclas", within_s: 5}
        |
        |  - id: race
        |    actor: ["manager[IT]", "employee[dept=IT, n=1]"]
        |    parallel: true
        |    do: "Eyni ticketi approve et"
        |    assert:
        |      - only_one_succeeds: true
        |
        |  - id: forbidden
        |    actor: employee[dept=IT, n=2]
        |    do: "Ticketi approve etməyə çalış"
        |    assert:
        |      - http_status: {path: "/api/tickets/{last_id}/approve", method: POST, equals: 403}
        |
        """.trimMargin()

    val MINI_SHA: String get() = ScenarioHash.of(MINI_YAML)

    /** [MINI_YAML] loaded by the real campaign loader. */
    val MINI_CAMPAIGN: Campaign by lazy { runBlocking { validator().check(MINI_YAML, "mini.yaml").validCampaign() } }

    /** The real loader and validator; [targetOverride] plays `PETEK_TARGET`, which replaces `campaign.target`. */
    fun validator(
        workDirectory: Path? = null,
        runFunctions: Set<String> = RUN_FUNCTIONS,
        targetOverride: URI? = null,
    ): ScenarioValidator =
        CampaignScenarioValidator(YamlCampaignSource(targetOverride), DefaultCampaignValidator(), runFunctions, workDirectory)

    // ---- evidence builders -----------------------------------------------------------------------------------------

    val RUN: RunId = RunId("run_1")
    val T0: Instant = Instant.parse("2026-01-01T10:00:00Z")

    fun run(
        hash: String = MINI_SHA,
        name: String = "mini",
        runId: RunId = RUN,
        result: RunResult = RunResult.FAILED,
    ) = RunRecord(
        runId = runId,
        runTag = RunTag("k7x2"),
        campaignHash = hash,
        campaignName = name,
        seed = 7,
        target = "https://staging.kadrohr.test",
        startedAt = T0,
        endedAt = T0.plusSeconds(600).takeIf { result != RunResult.RUNNING },
        result = result,
    )

    fun step(
        id: String,
        agent: String?,
        scenarioStep: String,
        kind: StepKind,
        action: String,
        status: StepStatus,
        detail: String? = null,
        reason: String? = null,
        second: Long = 0,
        runId: RunId = RUN,
    ) = StepRecord(
        stepId = StepId(id),
        runId = runId,
        agentId = agent?.let(::AgentId),
        scenarioStep = scenarioStep,
        kind = kind,
        action = action,
        llmReason = reason,
        startedAt = T0.plusSeconds(second),
        endedAt = T0.plusSeconds(second + 1),
        durationMs = 1_000,
        status = status,
        detail = detail,
        correlationId = CorrelationId("cor_${agent ?: "group"}_$scenarioStep"),
    )

    fun assertion(
        stepId: String,
        agent: String?,
        scenarioStep: String,
        type: String,
        verdict: Verdict,
        expected: String = "expected",
        observed: String? = "observed",
        source: EvidenceSource = EvidenceSource.RECEIVER,
        artifacts: List<String> = emptyList(),
        note: String? = null,
    ) = AssertionRecord(
        stepId = StepId(stepId),
        runId = RUN,
        agentId = agent?.let(::AgentId),
        scenarioStep = scenarioStep,
        type = type,
        source = source,
        expected = expected,
        observed = observed,
        verdict = verdict,
        latencyMs = null,
        note = note,
        artifactIds = artifacts.map(::ArtifactId),
    )

    fun finding(
        id: String,
        stepId: String?,
        scenarioStep: String,
        agent: String?,
        findingClass: FindingClass,
        note: String,
        artifacts: List<String> = emptyList(),
    ) = FindingRecord(
        findingId = FindingId(id),
        runId = RUN,
        stepId = stepId?.let(::StepId),
        scenarioStep = scenarioStep,
        agentId = agent?.let(::AgentId),
        findingClass = findingClass,
        a = "sender did it",
        b = "receiver saw nothing",
        c = null,
        note = note,
        artifactIds = artifacts.map(::ArtifactId),
    )

    fun artifact(
        id: String,
        stepId: String,
        type: ArtifactType = ArtifactType.SCREENSHOT,
    ) = ArtifactRecord(
        artifactId = ArtifactId(id),
        runId = RUN,
        stepId = StepId(stepId),
        type = type,
        relativePath = "run_1/$stepId-${type.name.lowercase()}.${type.extension}",
        sha256 = "0".repeat(64),
        sizeBytes = 10,
    )
}
