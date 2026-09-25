/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.domain

import az.petek.core.ids.RunId
import java.time.Instant

/**
 * Where a surprise comes from (docs/PLAN.md Faza 7):
 * - [SYSTEM_BUG]: the target is wrong; report it to its developers. The scenario stays as it is.
 * - [MODEL_GAP]: our knowledge of the site is wrong or outdated (a flow, page, selector, element or id source changed).
 * - [SCENARIO_BUG]: the scenario itself is wrong or ambiguous (task text, actor, assertion, expected value, timeout).
 */
enum class TriageCategory {
    SYSTEM_BUG,
    MODEL_GAP,
    SCENARIO_BUG,
    ;

    /** Only knowledge and scenario problems are fixed in the scenario; a system bug is fixed in the target. */
    val changesScenario: Boolean get() = this != SYSTEM_BUG
}

/**
 * Lifecycle of a proposed scenario change:
 * - [PENDING]: it applies to the triaged version and the result passes the campaign validator; not in a draft yet.
 * - [DRAFTED]: it is part of the v2 draft [ProposedChange.draftId] that triage created for the owner to review.
 * - [REJECTED]: it could not be used ([ProposedChange.rejection] says why); the verdict itself still stands.
 */
enum class ProposalStatus { PENDING, DRAFTED, REJECTED }

/** A scenario change the model proposed with a verdict, and what became of it. */
data class ProposedChange(
    val summary: String,
    val edits: List<YamlEdit>,
    val status: ProposalStatus,
    val rejection: String? = null,
    val draftId: ScenarioVersionId? = null,
) {
    init {
        require((status == ProposalStatus.REJECTED) == (rejection != null)) { "A rejection reason is given exactly for REJECTED" }
        require((status == ProposalStatus.DRAFTED) == (draftId != null)) { "A draft id is given exactly for DRAFTED" }
    }

    fun rejected(reason: String): ProposedChange = copy(status = ProposalStatus.REJECTED, rejection = reason, draftId = null)

    fun drafted(draft: ScenarioVersionId): ProposedChange = copy(status = ProposalStatus.DRAFTED, rejection = null, draftId = draft)
}

/**
 * The model's classification of one surprise, validated in code: [confidence] is in 0..1, [basedOn] only names
 * evidence the question showed (and is never empty), and a change is only ever pending or drafted for categories
 * that change the scenario. [scenarioVersionId] is the version the run executed; proposals are relative to it.
 */
data class TriageVerdict(
    val surpriseId: SurpriseId,
    val runId: RunId,
    val scenarioVersionId: ScenarioVersionId,
    val category: TriageCategory,
    val rationale: String,
    val confidence: Double,
    val basedOn: List<EvidenceRef>,
    val proposedChange: ProposedChange?,
    /** Model that answered, for audit. */
    val model: String,
    val decidedAt: Instant,
) {
    init {
        require(rationale.isNotBlank()) { "A verdict needs a rationale" }
        require(confidence in 0.0..1.0) { "confidence must be in 0..1, was $confidence" }
        require(basedOn.isNotEmpty()) { "A verdict must link to the evidence it is based on" }
        require(category.changesScenario || proposedChange == null || proposedChange.status == ProposalStatus.REJECTED) {
            "A $category verdict cannot carry a usable scenario change"
        }
    }
}

/** A surprise the model could not classify this time (invalid answer, model unavailable); it is retried next time. */
data class TriageFailure(
    val surpriseId: SurpriseId,
    val runId: RunId,
    val reason: String,
    val failedAt: Instant,
)
