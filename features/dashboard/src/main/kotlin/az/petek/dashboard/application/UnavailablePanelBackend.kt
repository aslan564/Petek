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

package az.petek.dashboard.application

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.CapacityView
import az.petek.dashboard.domain.DiffView
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.FindingView
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
import az.petek.dashboard.domain.TeardownView
import az.petek.dashboard.domain.TriageView
import az.petek.evidence.domain.ArtifactRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path

/**
 * The panel backend of a process that only watches: the live board works, every other screen shows that it is empty,
 * and every action explains that it is not available here, with [reason]. The default of `DashboardServer`, so the
 * board can be served before the rest of the panel is wired; `petek mcp` serves it without a configured site, with
 * [NO_TARGET] as the reason, so the host AI asks the owner which site to test instead of testing anything else.
 */
class UnavailablePanelBackend(
    private val reason: String = MESSAGE,
) : PanelBackend {
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

    override suspend fun findings(runId: RunId): List<FindingView> = emptyList()

    override suspend fun teardown(runId: RunId): TeardownView = unavailable()

    private fun unavailable(): Nothing = throw PanelUnavailableException(reason)

    companion object {
        const val MESSAGE = "Bu əməliyyat bu prosesdə qoşulmayıb: panel yalnız canlı lövhəni göstərir."

        /** No site under test was given: nothing can be explored or run until the owner names one. */
        const val NO_TARGET =
            "Test olunacaq sayt verilməyib. Sahibdən soruşun: hansı sayt test olunsun? Cavab gələnə qədər gözləyin; " +
                "sayt `.env`-də PETEK_TARGET kimi (və ya `petek init --target <url>` ilə) yazılandan sonra `petek mcp`-ni " +
                "yenidən başladın. Başqa sayt, saxta səhifə və ya uydurma nəticə ilə əvəz etməyin."
    }
}
