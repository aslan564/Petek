/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import java.time.Instant

/*
 * Versioned scenarios and triage as the "Ssenarilər" screen shows them (docs/PLAN.md Faza 7). The backend maps the
 * scenarios module's records into these views.
 */

/** FROZEN is immutable; approving a version supersedes the previous APPROVED one of the same name. */
enum class ScenarioStatus { DRAFT, APPROVED, FROZEN, SUPERSEDED }

enum class ScenarioSource { USER, EXPLORER, TRIAGE }

data class ScenarioVersionView(
    val id: String,
    val name: String,
    val version: Int,
    val status: ScenarioStatus,
    val source: ScenarioSource,
    /** The version this one was derived from (a triage proposal's v1, for example). */
    val parentId: String?,
    val note: String,
    val createdAt: Instant,
    val approvedAt: Instant?,
    val frozenAt: Instant?,
) {
    /** Only approved and frozen versions are run by default. */
    val runnable: Boolean get() = status == ScenarioStatus.APPROVED || status == ScenarioStatus.FROZEN
}

/** A version with its campaign YAML. */
data class ScenarioView(
    val version: ScenarioVersionView,
    val yaml: String,
)

enum class DiffLineKind { CONTEXT, ADDED, REMOVED, HUNK }

/** A line-based unified diff between two scenario versions ([from] → [to]), with context lines and hunk headers. */
data class DiffView(
    val from: ScenarioVersionView,
    val to: ScenarioVersionView,
    val lines: List<DiffLineView>,
) {
    val added: Int get() = lines.count { it.kind == DiffLineKind.ADDED }
    val removed: Int get() = lines.count { it.kind == DiffLineKind.REMOVED }
}

data class DiffLineView(
    val kind: DiffLineKind,
    val text: String,
    /** Line number in the old version (null for added lines and hunk headers). */
    val oldNumber: Int?,
    /** Line number in the new version (null for removed lines and hunk headers). */
    val newNumber: Int?,
)

/**
 * SYSTEM_BUG: the target is wrong (report it); MODEL_GAP: our knowledge of the site is wrong (a flow, selector or
 * element changed); SCENARIO_BUG: the scenario text or assertion is wrong or ambiguous.
 */
enum class TriageCategory { SYSTEM_BUG, MODEL_GAP, SCENARIO_BUG }

/** The triage of one run's surprises: what happened, what it means, and a v2 proposal when one was built. */
data class TriageView(
    val runId: RunId,
    val scenarioId: String?,
    val verdicts: List<TriageVerdictView>,
)

data class TriageVerdictView(
    val surpriseId: String,
    val scenarioStep: String,
    val agentId: AgentId?,
    /** PROBLEM_REPORTED, FAILED_STEP or FINDING. */
    val surpriseKind: String,
    val surprise: String,
    val category: TriageCategory,
    val rationale: String,
    /** 0.0 to 1.0. */
    val confidence: Double,
    val proposedChange: String?,
    /** The v2 DRAFT built from [proposedChange], when it passed the campaign validator. */
    val proposalScenarioId: String?,
    /** Screenshots and captures the verdict was based on. */
    val evidence: List<ArtifactId>,
)
