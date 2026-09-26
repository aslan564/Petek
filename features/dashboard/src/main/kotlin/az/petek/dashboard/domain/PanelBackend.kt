/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.domain

import az.petek.core.error.PetekException
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactRecord
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Everything the web panel asks of the rest of Pətək, besides the live board it gets from `LiveDashboard`.
 *
 * The composition root (`app`) implements it by adapting the capacity, explorer, scenarios, orchestration and
 * reporting use cases, so this module depends on none of them. It is split into four small ports, one per area, so an
 * implementation can be assembled from separate adapters with delegation
 * (`class AppPanel(...) : PanelBackend, PanelCapacity by capacity, PanelExplorer by explorer, ...`).
 *
 * Contract for every implementation:
 * - Safe to call from many coroutines at once. Reads are fast (no LLM, no browser); long work (explorations, runs,
 *   triage with an LLM) runs as a background job the backend owns and returns at once; progress arrives through
 *   [PanelExplorer.explorationUpdates] and the live board.
 * - One exploration and one run at a time: starting a second one fails with [PanelConflictException].
 * - Failures the owner should read are [PanelException]s with an Azerbaijani message and never carry secrets.
 * - Views never contain secrets or tester contact data.
 */
interface PanelBackend :
    PanelCapacity,
    PanelExplorer,
    PanelScenarios,
    PanelRuns,
    PanelManualCodes

/**
 * Codes the owner types in by hand (`PETEK_MAIL_SOURCE=manual`, "Kodu daxil et"): a tester waiting for a code is
 * listed until the owner answers it. Without the manual mail source there is never anything to answer.
 */
interface PanelManualCodes {
    /** Testers waiting for a code, oldest first. */
    fun manualCodes(): List<ManualCodeView> = emptyList()

    /** Gives [code] to request [id]; false when no such request waits or the code is not a plain code. */
    suspend fun answerManualCode(
        id: String,
        code: String,
    ): Boolean = false
}

/** One tester waiting for a code: the address the site sent it to (the owner's own box) and since when. */
data class ManualCodeView(
    val id: String,
    val address: String,
    val askedAt: java.time.Instant,
)

/** Capacity advice for the tester count on the instruction screen ("Təlimat"). */
fun interface PanelCapacity {
    suspend fun capacity(testers: Int): CapacityView
}

/** The explorer agent ("Kəşfiyyat"). */
interface PanelExplorer {
    /** Starts exploring [instructions]' target in the background; fails with [PanelRequestException] or [PanelConflictException]. */
    suspend fun startExploration(instructions: PanelInstructions): ExplorationView

    /** The current (or last) exploration; null when there has been none. */
    fun exploration(): ExplorationView?

    /** Every change of the current exploration, the latest always last. Hot: collectors see changes from when they start. */
    val explorationUpdates: Flow<ExplorationView>

    /** Answers one of the explorer's questions; the answer joins the instructions. */
    suspend fun answerUnknown(
        unknownId: String,
        answer: String,
    ): ExplorationView

    /** Stops the running exploration, keeping what it learned. False when none was running. */
    suspend fun cancelExploration(): Boolean

    /** What changed since the previous exploration of the same target; null when there is nothing to compare with. */
    suspend fun compareWithPrevious(): SiteModelDiffView?

    /** A screenshot or capture the current exploration recorded, so the panel may serve it; null for anything else. */
    suspend fun explorationArtifact(artifactId: ArtifactId): ArtifactRecord?
}

/** Versioned scenarios and triage ("Ssenarilər"). */
interface PanelScenarios {
    /** Turns the current exploration's draft into a new DRAFT scenario version. */
    suspend fun generateScenario(): ScenarioView

    /** Every version of every scenario, newest first within a name. */
    suspend fun scenarios(): List<ScenarioVersionView>

    suspend fun scenario(id: String): ScenarioView?

    /** Fails with [PanelNotFoundException] for an unknown id. */
    suspend fun diff(
        fromId: String,
        toId: String,
    ): DiffView

    /** DRAFT → APPROVED (superseding the name's previous approved version); fails with [PanelConflictException] otherwise. */
    suspend fun approve(id: String): ScenarioVersionView

    /** APPROVED → FROZEN; a frozen version never changes again. */
    suspend fun freeze(id: String): ScenarioVersionView

    /** The plan a run of the scenario would follow (steps, actors, emits → wait_for), for a preview before running. */
    suspend fun runPlan(scenarioId: String): RunPlanView?
}

/** Runs, their reports and their triage ("Hesabatlar"; the live run itself is on the board). */
interface PanelRuns {
    /** Starts a run in the background; one at a time. */
    suspend fun startRun(request: RunRequest): RunStartView

    /** Cancels the running run (teardown still happens). False when none was running. */
    suspend fun cancelRun(): Boolean

    /** Past and current runs, newest first. */
    suspend fun runs(): List<RunSummaryView>

    suspend fun stability(repeatGroup: String): StabilityView?

    /** The stored triage of a run; null when it was not triaged. */
    suspend fun triage(runId: RunId): TriageView?

    /** Triages a finished run's surprises (may take a while: one LLM question per surprise). */
    suspend fun runTriage(runId: RunId): TriageView

    /** Directory of a run's written report (`index.html`, `report.md`); null when there is none. */
    suspend fun reportDirectory(runId: RunId): Path?

    /**
     * The judged findings of a run with their three sources and evidence artifacts (which [PanelExplorer.explorationArtifact]
     * then resolves); empty for a run without findings or an unknown run.
     */
    suspend fun findings(runId: RunId): List<FindingView>

    /**
     * Deletes the test data a finished run created on its target (its test companies, through the target's test
     * API, which refuses anything that is not `is_test`). Fails with [PanelNotFoundException] for an unknown run and
     * [PanelConflictException] while the run is going or when it was made against another site.
     */
    suspend fun teardown(runId: RunId): TeardownView
}

/** A failure of a panel operation the owner should read; [message] is Azerbaijani and free of secrets. */
open class PanelException(
    message: String,
    cause: Throwable? = null,
) : PetekException(message, cause)

/** The request is invalid; [problems] name the fields. */
class PanelRequestException(
    val problems: List<FieldProblem>,
) : PanelException(problems.joinToString(" ") { it.message })

/** The request is valid but not now (a run is already going, the version is frozen, ...). */
class PanelConflictException(
    message: String,
) : PanelException(message)

class PanelNotFoundException(
    message: String,
) : PanelException(message)

/** The backend cannot do this at all (not wired in this build, or a dependency is down). */
class PanelUnavailableException(
    message: String,
) : PanelException(message)
