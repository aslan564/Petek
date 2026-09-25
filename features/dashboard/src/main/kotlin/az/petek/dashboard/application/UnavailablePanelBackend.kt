/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.application

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.CapacityView
import az.petek.dashboard.domain.DiffView
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelUnavailableException
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.RunStartView
import az.petek.dashboard.domain.RunSummaryView
import az.petek.dashboard.domain.ScenarioVersionView
import az.petek.dashboard.domain.ScenarioView
import az.petek.dashboard.domain.SiteModelDiffView
import az.petek.dashboard.domain.StabilityView
import az.petek.dashboard.domain.TriageView
import az.petek.evidence.domain.ArtifactRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path

/**
 * The panel backend of a process that only watches: the live board works, every other screen shows that it is empty,
 * and every action explains that it is not available here. The default of `DashboardServer`, so the board can be
 * served before the rest of the panel is wired.
 */
class UnavailablePanelBackend : PanelBackend {
    override suspend fun capacity(testers: Int): CapacityView = unavailable()

    override suspend fun startExploration(instructions: PanelInstructions): ExplorationView = unavailable()

    override fun exploration(): ExplorationView? = null

    override val explorationUpdates: Flow<ExplorationView> = emptyFlow()

    override suspend fun answerUnknown(
        unknownId: String,
        answer: String,
    ): ExplorationView = unavailable()

    override suspend fun cancelExploration(): Boolean = false

    override suspend fun compareWithPrevious(): SiteModelDiffView? = null

    override suspend fun explorationArtifact(artifactId: ArtifactId): ArtifactRecord? = null

    override suspend fun generateScenario(): ScenarioView = unavailable()

    override suspend fun scenarios(): List<ScenarioVersionView> = emptyList()

    override suspend fun scenario(id: String): ScenarioView? = null

    override suspend fun diff(
        fromId: String,
        toId: String,
    ): DiffView = unavailable()

    override suspend fun approve(id: String): ScenarioVersionView = unavailable()

    override suspend fun freeze(id: String): ScenarioVersionView = unavailable()

    override suspend fun runPlan(scenarioId: String): RunPlanView? = null

    override suspend fun startRun(request: RunRequest): RunStartView = unavailable()

    override suspend fun cancelRun(): Boolean = false

    override suspend fun runs(): List<RunSummaryView> = emptyList()

    override suspend fun stability(repeatGroup: String): StabilityView? = null

    override suspend fun triage(runId: RunId): TriageView? = null

    override suspend fun runTriage(runId: RunId): TriageView = unavailable()

    override suspend fun reportDirectory(runId: RunId): Path? = null

    private fun unavailable(): Nothing = throw PanelUnavailableException(MESSAGE)

    private companion object {
        const val MESSAGE = "Bu əməliyyat bu prosesdə qoşulmayıb: panel yalnız canlı lövhəni göstərir."
    }
}
