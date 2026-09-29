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

package az.petek.dashboard.domain

import az.petek.core.ids.RunId
import az.petek.evidence.domain.RunResult
import java.time.Instant

/**
 * "Test et" (Faza 25.3): the site is explored, the scenario is drafted from what the explorer found, approved and run,
 * in one go; the owner writes no scenario file. The step-by-step way (explore, look at the draft, approve, run) stays.
 * Backends without it keep the defaults: nothing starts.
 */
interface PanelTestFlow {
    /**
     * Starts the whole test of [instructions]' site in the background and answers at once; refused like an exploration
     * ([PanelRequestException], [PanelConflictException]) and while another test is going.
     */
    suspend fun startTest(instructions: PanelInstructions): TestFlowView = throw PanelUnavailableException(UNAVAILABLE)

    /** The current (or last) test; null when there has been none. */
    fun testFlow(): TestFlowView? = null

    /**
     * Stops the running test: the exploration or run it started is stopped as the owner's "Dayandır" stops them (what the
     * explorer learned is kept, a run is still torn down and reported). False when no test was running.
     */
    suspend fun cancelTest(): Boolean = false

    companion object {
        const val UNAVAILABLE = "\"Test et\" bu paneldə qoşulmayıb."
    }
}

/** Where a test is: exploring the site, drafting the scenario, running it, finished, or stopped (see [TestFlowView.note]). */
enum class TestStage {
    EXPLORING,
    DRAFTING,
    RUNNING,
    FINISHED,
    STOPPED,
    ;

    /** Nothing follows a final stage. */
    val isFinal: Boolean get() = this == FINISHED || this == STOPPED
}

/**
 * One test of a site ("Test et"): its [stage], the exploration, scenario version and run it made so far, the run's
 * [result] once it ended, and a [note] for the owner (in Azerbaijani): why it stopped, or how the run ended.
 */
data class TestFlowView(
    val target: String,
    val stage: TestStage,
    val startedAt: Instant,
    val explorationId: String? = null,
    val scenarioId: String? = null,
    val runId: RunId? = null,
    val result: RunResult? = null,
    val note: String? = null,
)
