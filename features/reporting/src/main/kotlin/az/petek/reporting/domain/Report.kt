/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord
import java.nio.file.Path

/**
 * The three-source rule (docs/PLAN.md, design decision 4). A = what the sender did, B = what receivers saw,
 * C = what the target says (oracle). A=B=C -> pass; A≠C -> backend; C≠B -> delivery/UI; A≠B with no C -> investigate.
 */
data class Observation(
    val source: String,
    val value: String?,
    val passed: Boolean?,
)

sealed interface JudgeVerdict {
    data object Pass : JudgeVerdict

    data class Finding(
        val findingClass: FindingClass,
        val note: String,
    ) : JudgeVerdict
}

interface Judge {
    fun classify(
        a: Observation?,
        b: Observation?,
        c: Observation?,
    ): JudgeVerdict

    /** Groups a run's assertion records per scenario step and actor and derives findings. */
    fun findings(
        run: RunRecord,
        assertions: List<AssertionRecord>,
    ): List<FindingRecord>

    /**
     * Like [findings], and additionally turns failed agent actions in [steps] into findings, so a run that broke
     * before any assertion (e.g. `mail_timeout` during registration) still explains itself. The default ignores
     * [steps], which keeps judges written against the two-argument contract valid.
     */
    fun findings(
        run: RunRecord,
        assertions: List<AssertionRecord>,
        steps: List<StepRecord>,
    ): List<FindingRecord> = findings(run, assertions)
}

data class StepRow(
    val scenarioStep: String,
    val agentId: String?,
    val agentName: String?,
    /** [az.petek.evidence.domain.StepKind] name. */
    val kind: String,
    /** [az.petek.evidence.domain.StepStatus] name. */
    val status: String,
    val durationMs: Long,
    val detail: String?,
    /** Artifact id of the step's last screenshot; resolve it through [ReportModel.artifactLinks]. */
    val screenshot: String?,
    /** The row belongs to an action that lost a race: expected, shown as such (see [ExpectedOutcomes.showsLostRace]). */
    val lostRace: Boolean = false,
    /** The row belongs to an action the target refused as the step expected (see [ExpectedOutcomes.showsRefusal]). */
    val refused: Boolean = false,
)

data class LatencyStats(
    val event: String,
    val receivers: Int,
    val received: Int,
    val missing: List<String>,
    val avgMs: Long?,
    val p95Ms: Long?,
    val maxMs: Long?,
    val perReceiverMs: Map<String, Long?>,
)

data class StabilityRow(
    val scenarioStep: String,
    val runs: Int,
    val passed: Int,
) {
    val passRate: Double get() = if (runs == 0) 0.0 else passed.toDouble() / runs
    val flaky: Boolean get() = passed in 1 until runs
}

data class FailedAgentRow(
    val agentId: String,
    val name: String,
    val scenarioStep: String,
    val reason: String,
)

data class ReportSummary(
    val stepsPassed: Int,
    val stepsFailed: Int,
    val assertionsPassed: Int,
    val assertionsFailed: Int,
    val assertionsSkipped: Int,
    val agents: Int,
    val durationMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double?,
    val realtimeTransports: List<String>,
    /** Prompt tokens served from the provider's cache; most of a run's prompt when the provider caches, so shown too. */
    val cacheReadTokens: Long = 0,
)

data class ReportModel(
    val run: RunRecord,
    val summary: ReportSummary,
    val steps: List<StepRow>,
    val assertions: List<AssertionRecord>,
    val latency: List<LatencyStats>,
    val findings: List<FindingRecord>,
    val failedAgents: List<FailedAgentRow>,
    /** Present when the run belongs to a `--repeat` group. */
    val stability: List<StabilityRow>?,
    /** Paths relative to the report directory, keyed by artifact id; an artifact that cannot be linked safely has none. */
    val artifactLinks: Map<String, String>,
    /** Token and cost accounting per agent, as recorded by the LLM metering (never estimated). */
    val usage: List<UsageRecord> = emptyList(),
)

/** Writes one report format into [directory] and returns the written file. */
interface ReportWriter {
    val fileName: String

    fun write(
        model: ReportModel,
        directory: Path,
    ): Path
}
