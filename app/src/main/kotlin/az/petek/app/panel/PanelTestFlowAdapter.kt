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

package az.petek.app.panel

import az.petek.app.panel.explorer.ExplorationTracker
import az.petek.app.panel.explorer.PanelExplorerAdapter
import az.petek.app.panel.explorer.PanelExplorerAdapter.StartedExploration
import az.petek.app.panel.runs.PanelRunsAdapter
import az.petek.app.panel.runs.PanelRunsAdapter.LaunchedRun
import az.petek.app.panel.scenarios.PanelScenariosAdapter
import az.petek.core.time.HarnessClock
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelTestFlow
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.RunSummaryView
import az.petek.dashboard.domain.TestFlowView
import az.petek.dashboard.domain.TestStage
import az.petek.evidence.domain.RunResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * "Test et" (Faza 25.3): the owner's one button. The site of the instruction form is explored; when the exploration
 * ends with a site model, the scenario is drafted from that exploration only (never an earlier one), approved and run
 * with the form's tester count on the same site; the test ends with the run. Each part is the ordinary panel operation
 * the step-by-step way uses ([PanelExplorerAdapter], [PanelScenariosAdapter], [PanelRunsAdapter]), so its rules, board
 * and records are the same, and the owner sees every part on its own screen as it happens.
 *
 * The test stops, with the reason in its note, where the step-by-step way would stop: an exploration that failed, was
 * stopped or saved no model, a draft that does not pass the validator, a run that is refused (another run going, a
 * site without proven ownership) or stopped. One test at a time; [cancelTest] stops the part that is running.
 */
internal class PanelTestFlowAdapter(
    private val explorer: PanelExplorerAdapter,
    private val scenarios: PanelScenariosAdapter,
    private val runs: PanelRunsAdapter,
    private val scope: CoroutineScope,
    private val clock: HarnessClock,
) : PanelTestFlow {
    private val lock = Any()
    private val starting = Mutex()
    private var view: TestFlowView? = null
    private var job: Job? = null

    override fun testFlow(): TestFlowView? = synchronized(lock) { view }

    override suspend fun startTest(instructions: PanelInstructions): TestFlowView =
        starting.withLock {
            if (synchronized(lock) { job?.isActive == true }) {
                throw PanelConflictException("Artıq bir test gedir. Bitməsini gözləyin və ya dayandırın.")
            }
            val exploration = explorer.begin(instructions)
            synchronized(lock) {
                val started = TestFlowView(instructions.target.trim(), TestStage.EXPLORING, clock.now().wall)
                view = started
                job = scope.launch { proceed(instructions, exploration) }
                started
            }
        }

    override suspend fun cancelTest(): Boolean {
        val running = synchronized(lock) { job?.takeIf { it.isActive } } ?: return false
        running.cancel()
        return true
    }

    private suspend fun proceed(
        instructions: PanelInstructions,
        exploration: StartedExploration,
    ) {
        var run: LaunchedRun? = null
        try {
            exploration.job.join()
            val explored = exploration.view()
            val explorationId = explored.id.takeUnless { it == ExplorationTracker.PREPARING_ID }
            update { it.copy(explorationId = explorationId) }
            val early = explorationStop(explored.status, explored.message)
            if (early != null || explorationId == null) return stop(early ?: "Kəşfiyyat başlamadı; ssenari yazılmadı.")
            val timedOut = explored.status == ExplorationStatus.TIMED_OUT
            val scouted = if (timedOut) "Kəşfiyyatın vaxtı bitdi; ssenari onun gördüklərindən yazıldı." else null
            update { it.copy(stage = TestStage.DRAFTING, note = scouted) }
            val draft = scenarios.generateFor(explorationId)
            update { it.copy(scenarioId = draft.version.id) }
            val approved = scenarios.approve(draft.version.id)
            update { it.copy(scenarioId = approved.id, stage = TestStage.RUNNING) }
            // Started without a cancellation point: a run whose caller is cancelled while it starts goes on unseen,
            // so a test stopped at that moment stops the run once it is known (at the join below).
            val launched =
                withContext(NonCancellable) {
                    runs.launch(RunRequest(scenarioId = approved.id, testers = instructions.testers, target = instructions.target.trim()))
                }
            run = launched
            update { it.copy(runId = launched.view.runId) }
            launched.join()
            ended(runs.summary(launched.view.runId), scouted)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (exploration.job.isActive) exploration.job.cancel()
                run?.cancel()
                stop("Test dayandırıldı.")
            }
            throw e
        } catch (e: PanelException) {
            stop(e.message.orEmpty())
        } catch (e: Exception) {
            logger.error(e) { "A test started with \"Test et\" failed" }
            stop("Test gözlənilmədən dayandı: ${e.message ?: e::class.simpleName}; ətraflı məlumat loqdadır.")
        }
    }

    /** Why an exploration that ended with [status] gives no draft to run; null when it does. */
    private fun explorationStop(
        status: ExplorationStatus,
        message: String?,
    ): String? =
        when (status) {
            ExplorationStatus.FINISHED, ExplorationStatus.TIMED_OUT -> null
            ExplorationStatus.CANCELLED -> "Kəşfiyyat dayandırıldı; ssenari yazılmadı və heç nə run olunmadı."
            ExplorationStatus.FAILED, ExplorationStatus.RUNNING -> message ?: "Kəşfiyyat alınmadı; ssenari yazılmadı."
        }

    /** The test's end by its run: FINISHED with the run's result, STOPPED when the run did not end on its own. */
    private fun ended(
        run: RunSummaryView?,
        scouted: String?,
    ) {
        val (stage, said) =
            when (run?.result) {
                RunResult.PASSED -> {
                    TestStage.FINISHED to "Test bitdi: run keçdi (${run.stepsPassed} addım keçdi). Hesabat hazırdır."
                }

                RunResult.FAILED -> {
                    TestStage.FINISHED to
                        "Test bitdi: run keçmədi (${run.stepsPassed} addım keçdi, ${run.stepsFailed} keçmədi, " +
                        "${run.assertionsFailed} yoxlama keçmədi). Səbəblər hesabatdadır."
                }

                RunResult.ABORTED -> {
                    TestStage.STOPPED to "Run dayandırıldı və ya yarımçıq qaldı; nə qədər getdiyi hesabatdadır."
                }

                RunResult.RUNNING, null -> {
                    TestStage.STOPPED to "Run gözlənilmədən dayandı; səbəb loglardadır (evidence/logs/petek.log)."
                }
            }
        update { it.copy(stage = stage, result = run?.result, note = listOfNotNull(scouted, said).joinToString(" ")) }
    }

    private fun stop(note: String) = update { it.copy(stage = TestStage.STOPPED, note = note) }

    private fun update(change: (TestFlowView) -> TestFlowView) =
        synchronized(lock) {
            view = view?.let(change)
        }
}
