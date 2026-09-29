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
import az.petek.explorer.domain.ExplorationId
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
 *
 * While the run goes, the explorer goes on from the model the run's scenario came from (Faza 18, the owner's decision
 * in LINK_ONLY_SWARM.md section 0.8): it asks only about pages new to that model and submits nothing. When the run
 * ends it stops too, and what it found new becomes the next run's scenario, a draft version waiting for the owner's
 * approval ([TestFlowView.nextScenarioId]); this run is never changed by it.
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

    /** Whether a test ("Test et") is going. */
    fun busy(): Boolean = synchronized(lock) { job?.isActive == true }

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
        var continuing: StartedExploration? = null
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
            // The explorer goes on while the run goes (the owner's decision, LINK_ONLY_SWARM.md section 0.8): what it
            // finds goes to the next run's scenario, never to this run.
            continuing = goOn(instructions, ExplorationId(explorationId))
            launched.join()
            val extended = continuing?.let { extend(it, ExplorationId(explorationId)) }
            ended(runs.summary(launched.view.runId), listOfNotNull(scouted, extended).joinToString(" ").ifEmpty { null })
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (exploration.job.isActive) exploration.job.cancel()
                continuing?.job?.cancel()
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

    /**
     * Starts the explorer again from exploration [from]'s model while the run goes; null when it cannot start (the reason
     * is logged: the run does not depend on it).
     */
    private suspend fun goOn(
        instructions: PanelInstructions,
        from: ExplorationId,
    ): StartedExploration? =
        try {
            explorer.begin(instructions, continueFrom = from)
        } catch (e: PanelException) {
            logger.info { "The explorer could not go on during the run: ${e.message}" }
            null
        }

    /**
     * Stops the exploration that went on during the run, now that the run ended, and drafts the next run's scenario from
     * what it found when it found anything new; the draft waits for the owner's approval. Returns the owner's note.
     */
    private suspend fun extend(
        continuing: StartedExploration,
        from: ExplorationId,
    ): String {
        explorer.endWithRun(continuing)
        val id = continuing.view().id.takeUnless { it == ExplorationTracker.PREPARING_ID }
        val after = id?.let { explorer.model(ExplorationId(it)) }
        val before = explorer.model(from)
        if (after == null || before == null) return "Run kəşfiyyatçı yenidən başlamamış bitdi; növbəti ssenari bu run-ınkı kimidir."
        val pages = after.pages.count { before.pageByPattern(it.urlPattern) == null }
        val actions = after.actions.count { before.action(it.id) == null }
        if (pages == 0 && actions == 0) return "Kəşfiyyatçı run zamanı yeni səhifə və ya əməliyyat tapmadı."
        return try {
            val next = scenarios.generateFor(id)
            update { it.copy(nextScenarioId = next.version.id) }
            "Kəşfiyyatçı run zamanı $pages yeni səhifə və $actions yeni əməliyyat tapdı; növbəti run üçün ssenari layihəsi " +
                "hazırdır (${next.version.id}), təsdiqinizi gözləyir."
        } catch (e: PanelException) {
            "Kəşfiyyatçı run zamanı $pages yeni səhifə tapdı, amma növbəti ssenari yazılmadı: ${e.message}"
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
