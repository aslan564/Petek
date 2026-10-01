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

package az.petek.orchestration.application

import az.petek.agent.application.TesterAgentFactory
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.AgentVariables
import az.petek.agent.domain.Colleague
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.TestMail
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserProxy
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TemplateRenderer
import az.petek.campaign.domain.apiOriginInUse
import az.petek.core.ids.AgentId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.core.model.RegistrationMode
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.ABORT_ACTION
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.CAPACITY_ACTION
import az.petek.evidence.domain.COVERAGE_ACTION
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.NotReached
import az.petek.evidence.domain.ROSTER_ACTION
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResource
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UNCOVERED_ACTION
import az.petek.identity.application.PlanIdentitiesUseCase
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityRegistryGenerator
import az.petek.identity.domain.IdentityRepository
import az.petek.identity.domain.IdentityStatus
import az.petek.oracle.domain.JsonFieldSelector
import az.petek.oracle.domain.TargetOracle
import az.petek.orchestration.domain.ActorResolver
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.domain.WavePlan
import az.petek.orchestration.domain.Waves
import az.petek.verification.application.VerifyStepUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Runs one campaign end to end (docs/ARCHITECTURE.md "Run lifecycle"):
 * 1. creates the run record and the identity registry (tag derived from the run id);
 * 2. starts the browser and gives every identity its own session and [az.petek.agent.application.TesterAgent];
 * 3. executes the setup steps, then the main steps, in order and within the campaign's time budget
 *    (see [StepExecutor] for what happens inside a step). In a `wait_for` step, `visible_text`/`latency_max` are
 *    checked as soon as the event arrives, before the receiver's own action, so t1 - t0 measures delivery rather
 *    than the agent; the other assertions run after the action;
 * 4. always — also on abort, budget timeout or cancellation — records why the run stopped early, the roll call and the
 *    steps nobody ran (see "Nobody left out" below), the observed real-time transports, tears down the test company
 *    (unless `keepData`), closes sessions, stops the browser, finishes the run record and asks the [RunFinalizer] for
 *    the report.
 *
 * Setup semantics: an actor that fails a setup step is marked FAILED with the failure key and skipped (with a
 * SKIPPED record) in every later step; a successful sign-up/login step marks it ACTIVE
 * ([RunnerSettings.activatingRunFunctions], or any setup `do` step). If the admin fails a setup step nothing else
 * can work, so the run is ABORTED. `on_fail: abort` (step or campaign level) aborts after a failed step.
 *
 * Nobody left out: at the start the evidence gets the run's roster (every planned tester, `roster`) and, when the caller
 * knew it, this machine's capacity next to the run's size (`capacity`, no verdict). The runner plans every execution of
 * the steps up front (the run, or each wave; the account swap when it begins) and, at the end, gives every planned
 * tester × step without a final record of its own one `not_reached` record saying why (the run stopped, its wave never
 * began, it was out since an earlier failure); a step that no tester ran in any execution gets an agent-less `uncovered`
 * record that counts as a failed step, so the run cannot pass with it. The action names and their details are in the
 * evidence domain ([ROSTER_ACTION], [CAPACITY_ACTION], [ABORT_ACTION], [NOT_REACHED_ACTION], [UNCOVERED_ACTION]).
 *
 * Result: PASSED when no step and no assertion failed, FAILED otherwise, ABORTED on abort, budget timeout,
 * cancellation or an infrastructure error (which is recorded, logged and not rethrown; cancellation of the caller
 * and fatal [Error]s are rethrown after cleanup). A `permission_denied` refusal in a main step is not a failure by
 * itself: permission tests expect it and their assertions decide.
 *
 * Live views: besides the agent board, the [monitor] gets the orchestrator's task plan ([MonitorView.planReady], sent
 * once the agents' sessions are open and again before a step whenever the agents it resolves to changed), every
 * step × agent task transition ([MonitorView.taskUpdated]; tasks a run never reached end SKIPPED) and every event
 * published and received.
 *
 * Wiring: every browser call an agent makes counts as progress for [watchdog] (the runner hands each agent a
 * progress-reporting view of its session). Wrap the recorder given to the agent loop and run functions in a
 * [ProgressTrackingRecorder] reporting to the same [watchdog] as well, so that recorded evidence also counts (e.g. a
 * run function that waits for an e-mail but records its sub-steps). Waiting for one of the AI slots the testers share
 * never counts as inactivity, however many testers queue for them, and neither does a slow answer the provider's own
 * timeout allows (see [InactivityWatchdog]). The run's [EventBus] comes from [busFactory] (one per run);
 * [sharedStateFactory] creates the run's [SharedRunState].
 */
class DefaultCampaignRunner(
    identityGenerator: IdentityRegistryGenerator,
    private val identities: IdentityRepository,
    private val runs: RunRepository,
    private val recorder: EvidenceRecorder,
    artifacts: ArtifactStore,
    private val browser: BrowserEngine,
    private val browserConfig: BrowserEngineConfig,
    private val agents: TesterAgentFactory,
    private val verify: VerifyStepUseCase,
    private val oracle: TargetOracle,
    fields: JsonFieldSelector,
    private val renderer: TemplateRenderer,
    private val actors: ActorResolver,
    private val monitor: MonitorView,
    private val finalizer: RunFinalizer,
    private val clock: HarnessClock,
    private val ids: IdGenerator,
    private val settings: RunnerSettings,
    private val sharedStateFactory: () -> SharedRunState,
    private val watchdog: InactivityWatchdog = InactivityWatchdog(),
    private val busFactory: () -> EventBus = { InProcessEventBus(clock, ids) },
    private val diagnostics: DiagnosticContext = DiagnosticContext.NONE,
) : CampaignRunner {
    private val evidence = HarnessEvidence(recorder, artifacts, ids, clock)
    private val objectIds = ObjectIdReader(oracle, fields, renderer)
    private val teardown = OracleTeardownUseCase(runs, oracle)
    private val identityPlanner = PlanIdentitiesUseCase(identityGenerator, identities)

    override suspend fun run(
        campaign: Campaign,
        options: RunOptions,
    ): RunSummary {
        val runId = ids.runId()
        val startedAt = clock.now()
        runs.create(
            RunRecord(
                runId = runId,
                runTag = RunTags.forRun(runId),
                campaignHash = campaign.sourceHash,
                campaignName = campaign.settings.name,
                seed = campaign.settings.seed,
                target = campaign.settings.target.toString(),
                startedAt = startedAt.wall,
                repeatGroup = options.repeatGroup,
                repeatIndex = options.repeatIndex,
                release = options.release,
            ),
        )
        val run = RunState(runId, campaign, options, startedAt, busFactory(), sharedStateFactory())
        val board = AgentBoard(monitor, clock)
        val tasks = TaskBoard(monitor, clock)
        lateinit var summary: RunSummary
        try {
            withContext(diagnostics.of(runId, null)) { execute(run, board, tasks) }
        } catch (e: Exception) {
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                run.abort("run cancelled")
                throw e
            }
            // Includes a stray CancellationException from a callee while the run itself was not cancelled.
            logger.error(e) { "run $runId stopped by an unexpected error" }
            run.abort("unexpected error: ${e::class.simpleName}: ${e.message}")
        } catch (e: Throwable) {
            // A fatal error (e.g. an unimplemented function) must never leave the run recorded as PASSED.
            run.abort("fatal error: ${e::class.simpleName}: ${e.message}")
            throw e
        } finally {
            summary = withContext(NonCancellable + diagnostics.of(runId, null)) { conclude(run, board, tasks) }
        }
        return summary
    }

    private suspend fun execute(
        run: RunState,
        board: AgentBoard,
        tasks: TaskBoard,
    ) {
        // What the scenario leaves unchecked goes into the evidence once, so the report names it (2026-09-30).
        if (run.campaign.coverage.isNotEmpty()) {
            evidence.system(run, null, COVERAGE_ACTION, StepStatus.SKIPPED, run.campaign.coverage.joinToString("\n"))
        }
        val completed =
            withTimeoutOrNull(run.budget) {
                planIdentities(run)
                val waves = Waves.plan(run.campaign, run.identities, actors)
                val live = waves?.maxLive ?: run.identities.size
                planPasses(run, waves)
                recordRoster(run, board, live)
                val proxies = proxies(run)
                if (settings.proxies.isNotEmpty() && proxies.isEmpty()) {
                    board.message(
                        "PETEK_PROXIES is not used in this run: only a site whose owner proved it is theirs gets testers from " +
                            "separate IPs (Faza 21); every tester goes out from this machine's IP",
                    )
                }
                if (proxies.isNotEmpty() && proxies.size < live) {
                    run.abort(
                        "each tester should come from its own IP, but $live testers are live at once and only " +
                            "${proxies.size} proxies are given (PETEK_PROXIES); give more or set a smaller campaign.wave_size",
                    )
                    return@withTimeoutOrNull true
                }
                if (waves == null) {
                    startAgents(run, board, run.identities)
                    runSteps(run, board, tasks)
                    if (run.options.swapAccounts) swapAccounts(run, board, tasks)
                } else {
                    runWaves(run, board, tasks, waves)
                    if (run.options.swapAccounts) {
                        // The swap hands accounts on among the testers of one run; waves close their browsers as they go.
                        val reason = "the account swap is not done in a run with waves (campaign.wave_size)"
                        evidence.system(run, null, "swap_accounts", StepStatus.SKIPPED, reason)
                        board.message(reason)
                    }
                }
                reportCoverage(run)
                true
            }
        if (completed == null) {
            run.abort("time budget of ${run.campaign.settings.budget.maxMinutes} min exceeded")
        }
    }

    /**
     * Receivers skipped because their wave had no tester for the step that emits their event (Faza 24.7) are counted
     * per step: a step some receivers could wait for says how many did; one no receiver could wait for anywhere is
     * `not_covered`, a failure of the run, since the campaign asked for a check no wave could make.
     */
    private suspend fun reportCoverage(run: RunState) {
        val coverage = synchronized(run.receivers) { run.receivers.toList() }
        coverage.forEach { (stepId, receivers) ->
            val skipped = receivers.withoutEmitter.get()
            if (skipped == 0) return@forEach
            val waited = receivers.waited.get()
            val detail =
                "$waited of ${waited + skipped} receivers could wait for the event of '$stepId'; $skipped had no tester of " +
                    "the step that emits it beside them (in their wave, or left in the run)"
            if (waited > 0) {
                evidence.system(run, null, "coverage", StepStatus.PASSED, detail, stepId)
            } else {
                evidence.system(run, null, "coverage", StepStatus.FAILED, "$NOT_COVERED: $detail", stepId, tally = Tally.FAIL)
            }
        }
    }

    // --- 1. identities --------------------------------------------------------------------------------------------

    private suspend fun planIdentities(run: RunState) {
        val quotas = run.campaign.settings
        val spec =
            CampaignIdentities.spec(
                quotas,
                settings.mailDomain,
                settings.mailbox,
                settings.accounts(quotas.target),
            )
        val plan = identityPlanner.execute(run.runId, RunTags.forRun(run.runId), spec)
        run.identities = plan.identities.sortedBy { it.agentId }
    }

    /**
     * Every execution of the steps this run will make, with the testers each step resolves its actors against there
     * (the roll call at the end reads it): the run's single pass, or each wave as [runWaves] runs it.
     */
    private fun planPasses(
        run: RunState,
        waves: WavePlan?,
    ) {
        if (waves == null) {
            run.passes += PlannedPass(1, run.campaign.allSteps.map { PassStep(it, run.identities) })
            run.passBegan(1)
            return
        }
        waves.waves.forEachIndexed { index, wave ->
            val steps =
                if (index == 0) {
                    run.campaign.allSteps.map { PassStep(it, waves.live(0)) }
                } else {
                    val later = waveSteps(run, wave)
                    later.setup.map { PassStep(it, wave) } + later.again.map { PassStep(it, waves.live(index)) }
                }
            run.passes += PlannedPass(index + 1, steps, wave = index + 1, waves = waves.waves.size)
        }
    }

    /**
     * The run's roster (`100 testers: a01, a02, ...`) and, when the caller knew this machine's capacity, the run's size
     * next to it: [live] testers at once (waves keep it below the whole run). Neither changes a verdict.
     */
    private suspend fun recordRoster(
        run: RunState,
        board: AgentBoard,
        live: Int,
    ) {
        val testers = run.identities.map { it.agentId.value }
        evidence.system(run, null, ROSTER_ACTION, StepStatus.PASSED, "${testers.size} testers: ${testers.joinToString(", ")}")
        val advice = run.options.capacityAdvice ?: return
        val numbers = "$live testers at once (${testers.size} in the run); this machine is advised for up to $advice at once"
        if (live <= advice) {
            evidence.system(run, null, CAPACITY_ACTION, StepStatus.PASSED, "$WITHIN_CAPACITY: $numbers")
        } else {
            val detail = "$OVER_CAPACITY: $numbers, so slow pages and late screens may come from this machine, not from the site"
            evidence.system(run, null, CAPACITY_ACTION, StepStatus.SKIPPED, detail)
            board.message(detail)
        }
    }

    // --- 2. browser and agents ------------------------------------------------------------------------------------

    /**
     * `campaign.wave_size` (Faza 21, 24.11): each wave opens the browsers of its testers, runs every step with them and
     * closes them, with its own event bus, so a live event never crosses waves. The residents ([WavePlan.residents], a
     * role nobody else has, such as the company's owner) are live in every wave: their browsers open with the first wave
     * and stay, their setup steps run in the first wave only, and every wave's main steps have them, so an
     * announcement is made and read in every wave. The events of setup steps are carried to every later wave's bus.
     * What the run shares (the company code, invitation links) stays shared. The board announces every tester once, at
     * the first wave. Proxies: the residents keep the first ones, each wave's testers take the next.
     */
    private suspend fun runWaves(
        run: RunState,
        board: AgentBoard,
        tasks: TaskBoard,
        plan: WavePlan,
    ) {
        board.start(run.runId, run.identities)
        val residents = plan.residents
        for ((index, wave) in plan.waves.withIndex()) {
            if (run.aborted) break
            run.passBegan(index + 1)
            val live = plan.live(index).map { it.agentId }.toSet()
            // `{pass}`: every wave publishes its own texts; a carried setup event keeps the pass it was written in.
            run.pass = index + 1
            if (index > 0) {
                run.bus = busFactory()
                run.setupEvents.forEach { run.bus.carry(it) }
            }
            val detail = waveDetail(index, plan)
            evidence.system(run, null, "wave", StepStatus.PASSED, detail)
            board.message(detail)
            if (index == 0) {
                startAgents(run, board, residents + wave, announce = false)
                run.wave = live
                runSteps(run, board, tasks)
            } else {
                startAgents(run, board, wave, announce = false, firstProxy = residents.size)
                // The residents set up in the first wave; this wave's testers set up on their own, then everyone acts.
                val later = waveSteps(run, wave)
                run.wave = wave.map { it.agentId }.toSet()
                runSteps(run, board, tasks, later.setup)
                run.wave = live
                val done = later.done
                if (done.isNotEmpty()) {
                    evidence.system(
                        run,
                        null,
                        "wave",
                        StepStatus.SKIPPED,
                        "wave ${index + 1}: ${done.joinToString { it.id }} not repeated: done by the testers in every wave " +
                            "alone, with no event of this wave, so the first wave did it already",
                    )
                }
                runSteps(run, board, tasks, later.again)
            }
            if (index < plan.waves.lastIndex) {
                val leaving = wave.map { it.agentId }.toSet()
                recordNetworkObservations(run, leaving)
                closeSessions(run, leaving)
                wave.forEach {
                    run.sessions.remove(it.agentId)
                    run.agents.remove(it.agentId)
                    if (!run.isFailed(it.agentId)) board.update(it.agentId, AgentState.DONE, null, null)
                }
            }
        }
    }

    /** What a wave after the first runs: [setup] with its own testers, then [again] with everyone live; [done] is not repeated. */
    private class LaterWave(
        val setup: List<ScenarioStep>,
        val again: List<ScenarioStep>,
        val done: List<ScenarioStep>,
    )

    /**
     * The steps of a wave after the first, for its own testers [wave]: the setup steps any of them acts in, and the main
     * steps that [repeats] (the others the first wave did already).
     */
    private fun waveSteps(
        run: RunState,
        wave: List<Identity>,
    ): LaterWave {
        val (again, done) = run.campaign.steps.partition { step -> repeats(step, wave, run.campaign) }
        return LaterWave(run.campaign.setup.filter { step -> actors.resolve(step.actors, wave).isNotEmpty() }, again, done)
    }

    /**
     * Whether [step] runs again in a later wave with its [wave] testers: always when any of them acts in it; a step the
     * residents do alone only when it emits (the wave's receivers wait for it) or waits for an event of a main step
     * (each wave has its own). Otherwise it would redo what the residents did in the first wave on the same objects: a
     * race between the owner and the only manager on a setup object would find it decided and fail for no fault of the
     * site.
     */
    private fun repeats(
        step: ScenarioStep,
        wave: List<Identity>,
        campaign: Campaign,
    ): Boolean {
        if (actors.resolve(step.actors, wave).isNotEmpty()) return true
        val waveEvents = campaign.steps.mapNotNullTo(HashSet()) { it.emits?.event }
        return step.emits != null || step.waitFor?.event in waveEvents
    }

    /** `wave 2 of 3: 4 testers (a03, a07, a11, a15); in every wave: a01 (admin), set up in wave 1`. */
    private fun waveDetail(
        index: Int,
        plan: WavePlan,
    ): String =
        buildString {
            val wave = plan.waves[index]
            append("wave ${index + 1} of ${plan.waves.size}: ${wave.size} testers (${wave.joinToString(", ") { it.agentId.value }})")
            if (plan.residents.isNotEmpty()) {
                append("; in every wave: ")
                append(plan.residents.joinToString(", ") { "${it.agentId} (${it.role.key})" })
                if (index > 0) append(", set up in wave 1")
            }
        }

    /**
     * The account swap ([RunOptions.swapAccounts], Faza 18): the testers that finished the main steps without failing
     * pass their accounts on in a ring (tester *i* takes the account of tester *i + 1*); a tester that failed a main
     * step sits out, saying why. Each old browser is closed first, so an account is never in two browsers; each account
     * then opens in a new browser with its saved storage state and a new agent, starts on the site's home page (not on
     * a blank page), and the main steps run once more as `<step>@swap` with the swapped testers only. A swap needs at
     * least two finished testers.
     */
    private suspend fun swapAccounts(
        run: RunState,
        board: AgentBoard,
        tasks: TaskBoard,
    ) {
        if (run.aborted) return
        val active = run.activeIdentities().filter { run.status(it.agentId) != IdentityStatus.FAILED }
        val finished = active.filterNot { run.tally.hasFailures(it.agentId) }
        if (finished.size < 2) {
            evidence.system(run, null, "swap_accounts", StepStatus.SKIPPED, "fewer than two testers finished; no account to swap")
            return
        }
        val factory = run.factory ?: return
        // Planned before any account moves, so a swap that stops half-way still accounts for every swapped tester.
        val again = run.campaign.steps.map { it.copy(id = it.id + SWAP_SUFFIX) }
        val swap = run.pass + 1
        run.passes += PlannedPass(swap, again.map { PassStep(it, finished) }, label = "swap")
        run.passBegan(swap)
        (active - finished.toSet()).forEach {
            evidence.system(run, it.agentId, "swap_accounts", StepStatus.SKIPPED, "${it.agentId} failed a main step and sits out the swap")
        }
        finished.forEach { identity ->
            run.sessions.remove(identity.agentId)?.let { session ->
                safely(run, "closing the session of ${identity.agentId}") { session.close() }
            }
            run.agents.remove(identity.agentId)
        }
        val colleagues = run.identities.map(Colleague::of)
        finished.forEachIndexed { index, tester ->
            val account = finished[(index + 1) % finished.size]
            evidence.system(
                run,
                account.agentId,
                "swap_accounts",
                StepStatus.PASSED,
                "tester ${tester.agentId} continues with the account of ${account.agentId} (${account.role.key}) in a new browser",
            )
            // The account keeps the address it had (Faza 21): the site sees one user from one IP, as before the swap.
            openAgent(run, factory, account, colleagues, run.proxies[account.agentId], restoreSession = true)
            openSite(run, account.agentId)
        }
        board.message("accounts swapped among ${finished.size} testers; the main steps run again")
        run.wave = finished.map { it.agentId }.toSet()
        // `{pass}`: the swap writes new texts, so the first pass's, still on the pages, are not taken for them.
        run.pass = swap
        try {
            runSteps(run, board, tasks, again)
        } finally {
            run.wave = null
        }
    }

    /**
     * A swapped account starts on the site's home page with its saved session, as its owner would, so its first step
     * sees the site rather than a blank page. Where it landed is recorded (a sign-in page there means the saved session
     * no longer holds); a site that cannot be opened at all takes the tester out of the swap.
     */
    private suspend fun openSite(
        run: RunState,
        agentId: AgentId,
    ) {
        val session = run.sessions[agentId] ?: return
        try {
            session.navigate("/")
            evidence.system(run, agentId, "swap_open", StepStatus.PASSED, "the saved session opened ${session.currentUrl()}")
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val reason = FailureReason.BROWSER_ERROR.key
            val detail = "$reason: the site did not open with the saved session: ${e::class.simpleName}: ${e.message}"
            evidence.system(run, agentId, "swap_open", StepStatus.ERROR, detail, tally = Tally.FAIL)
            markFailed(run, agentId, reason)
        }
    }

    /**
     * The owner's proxies this run may use (Faza 21): all of them on a site whose ownership is proven
     * ([RunOptions.ownSite]), none anywhere else.
     */
    private fun proxies(run: RunState): List<BrowserProxy> = if (run.options.ownSite) settings.proxies else emptyList()

    /** Opens the browsers and agents of [identities]; the n-th of them goes out through proxy [firstProxy] + n, if any. */
    private suspend fun startAgents(
        run: RunState,
        board: AgentBoard,
        identities: List<Identity>,
        announce: Boolean = true,
        firstProxy: Int = 0,
    ) {
        // Set first: a browser that fails half-way through starting is still stopped at the end.
        run.browserStarted = true
        val factory = run.factory ?: browser.start(browserConfig).also { run.factory = it }
        // One roster for everyone: who the colleagues are, never their secrets (computed once, shared read-only).
        val colleagues = run.identities.map(Colleague::of)
        val proxies = proxies(run)
        coroutineScope {
            identities
                .mapIndexed { index, identity ->
                    val proxy = proxies.getOrNull(firstProxy + index)?.also { run.proxies[identity.agentId] = it }
                    async(diagnostics.of(run.runId, identity.agentId)) { openAgent(run, factory, identity, colleagues, proxy) }
                }.awaitAll()
        }
        if (announce) board.start(run.runId, identities)
        identities
            .filter { run.isFailed(it.agentId) }
            .forEach { board.update(it.agentId, AgentState.FAILED, null, "browser session could not be opened") }
    }

    private suspend fun openAgent(
        run: RunState,
        factory: BrowserSessionFactory,
        identity: Identity,
        colleagues: List<Colleague>,
        proxy: BrowserProxy? = null,
        restoreSession: Boolean = false,
    ) {
        val agentId = identity.agentId
        try {
            val target = run.campaign.settings.target
            val targetHost = target.host?.lowercase()
            // The site's API on its own host, which the run's start checked like the target (2026-09-30).
            val api = run.campaign.apiOriginInUse
            val options =
                SessionOptions(
                    label = agentId.value,
                    baseUrl = target,
                    localStorage = run.campaign.target.localStorage,
                    correlationHeader = settings.correlationHeader,
                    proxy = proxy,
                    // No tester page opens a production host or writes to one; the target and its API only when allowed.
                    blockedHosts =
                        settings.productionHosts(target).map { it.trim().lowercase() }.toSet() -
                            setOfNotNull(targetHost, api?.host?.lowercase()),
                    apiOrigin = api,
                )
            val stored = storageStatePath(run.runId, agentId)

            // Looked up when the session opens, never before: the tester signs in (and saves its state) after this.
            fun signedIn() = options.copy(storageState = stored.takeIf(Files::isRegularFile))
            val restoredWith = AtomicReference<Path?>(null)
            val session =
                RestoringBrowserSession(
                    initial = factory.open(if (restoreSession) signedIn() else options),
                    // Same identity, and still signed in when it has signed in by now: its saved storage state (rule 7).
                    reopen = { factory.open(signedIn().also { restoredWith.set(it.storageState) }) },
                    onRestored = { count, reason, url ->
                        val back = url?.let { ", back on $it" }.orEmpty()
                        val state = if (restoredWith.get() != null) STORAGE_STATE_LOADED else NO_STORAGE_STATE
                        evidence.system(
                            run,
                            agentId,
                            RESTORE_SESSION,
                            StepStatus.PASSED,
                            "browser context lost ($reason); restored ($count) with the same identity, $state$back",
                        )
                    },
                )
            run.sessions[agentId] = session
            val runtime =
                AgentRuntime(
                    runId = run.runId,
                    identity = identity,
                    roster = colleagues,
                    session = ProgressReportingSession(session) { watchdog.progress(agentId) },
                    target = run.campaign.target,
                    variables = AgentVariables(),
                    shared = run.shared,
                    runStartedAt = run.startedAt.wall,
                    storageStatePath = stored,
                    testMail = TestMail.of(settings.mailDomain, settings.mailbox),
                    siteHosts = setOfNotNull(targetHost) + settings.allowedHosts(target).map { it.trim().lowercase() },
                )
            run.agents[agentId] = agents.create(runtime)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val reason = FailureReason.BROWSER_ERROR.key
            val detail = "$reason: ${e::class.simpleName}: ${e.message}"
            evidence.system(run, agentId, "open_session", StepStatus.ERROR, detail, tally = Tally.FAIL)
            markFailed(run, agentId, reason)
            if (identity.registration == RegistrationMode.OWNER) run.abort("the admin's browser session could not be opened")
        }
    }

    private fun storageStatePath(
        runId: RunId,
        agentId: AgentId,
    ): Path = settings.storageRoot.resolve(runId.value).resolve("${agentId.value}.json")

    // --- 3. steps -------------------------------------------------------------------------------------------------

    private suspend fun runSteps(
        run: RunState,
        board: AgentBoard,
        tasks: TaskBoard,
        steps: List<ScenarioStep> = run.campaign.allSteps,
    ) {
        val executor = StepExecutor(run, services(board, tasks))
        for (step in steps) {
            if (run.aborted) break
            // Announced again only when an agent that failed meanwhile changes who runs the remaining steps.
            tasks.announce(RunPlans.of(run, actors))
            run.startedSteps += step.id
            val result = executor.execute(step)
            if (step.phase == StepPhase.SETUP) applySetupOutcome(run, board, result)
            registerCompany(run)
            if (result.failed && (step.onFail ?: run.campaign.settings.onFail) == OnFail.ABORT) {
                run.abort("step '${step.id}' failed and on_fail is abort")
            }
        }
    }

    private fun services(
        board: AgentBoard,
        tasks: TaskBoard,
    ) = StepServices(
        resolver = actors,
        renderer = renderer,
        verify = verify,
        watchdog = watchdog,
        objectIds = objectIds,
        recorder = recorder,
        evidence = evidence,
        board = board,
        tasks = tasks,
        clock = clock,
        ids = ids,
        diagnostics = diagnostics,
    )

    private suspend fun applySetupOutcome(
        run: RunState,
        board: AgentBoard,
        result: StepResult,
    ) {
        val step = result.step
        for (actor in result.actors) {
            val agentId = actor.identity.agentId
            val failureKey = actor.failureKey
            if (failureKey in FailureReason.SITE_DEFECT_KEYS) {
                // A check found a defect of the site (a page checked before signing in): a finding, and the tester goes on.
                board.message("$agentId found a defect of the site in setup step '${step.id}'; it stays in the run")
            } else if (failureKey != null) {
                markFailed(run, agentId, failureKey)
                board.update(agentId, AgentState.FAILED, step.id, "failed setup: $failureKey")
                board.message("$agentId failed setup step '${step.id}' ($failureKey) and is excluded from later steps")
                if (actor.identity.registration ==
                    RegistrationMode.OWNER
                ) {
                    run.abort("the admin failed setup step '${step.id}' ($failureKey)")
                }
            } else if (activates(step) && run.status(agentId) != IdentityStatus.ACTIVE) {
                run.setStatus(agentId, IdentityStatus.ACTIVE)
                identities.updateStatus(run.runId, agentId, IdentityStatus.ACTIVE)
                identities.updateStorageState(run.runId, agentId, storageStatePath(run.runId, agentId).toString())
            }
        }
    }

    private fun activates(step: ScenarioStep): Boolean =
        when (val action = step.action) {
            is StepAction.Do -> true
            is StepAction.Run -> action.function in settings.activatingRunFunctions
            StepAction.None -> false
        }

    private suspend fun markFailed(
        run: RunState,
        agentId: AgentId,
        reason: String,
    ) {
        run.markFailed(agentId, reason)
        identities.updateStatus(run.runId, agentId, IdentityStatus.FAILED, reason)
    }

    /** Registers the company the run created (published by the admin's setup functions) for teardown. */
    private suspend fun registerCompany(run: RunState) {
        run.shared.get(SharedRunState.COMPANY_ID)?.let { registerCompany(run, it) }
    }

    private suspend fun registerCompany(
        run: RunState,
        companyId: String,
    ) {
        if (run.companies.add(companyId)) {
            runs.addResource(RunResource(run.runId, OracleTeardownUseCase.COMPANY, companyId, clock.now().wall))
        }
    }

    /**
     * A run that stopped before the company id was shared (e.g. the owner signed up through the UI and the next
     * step crashed) still created a company; find it by its owner so teardown does not leave it on the target.
     */
    private suspend fun discoverCompany(run: RunState) {
        if (run.companies.isNotEmpty() || run.startedSteps.isEmpty() || !oracle.isAvailable) return
        for (owner in run.identities.filter { it.registration == RegistrationMode.OWNER }) {
            val company = oracle.companyByOwner(owner.email) ?: continue
            if (company.isTest) {
                registerCompany(run, company.id)
            } else {
                logger.warn { "run ${run.runId}: company ${company.id} of ${owner.agentId} is not is_test; it is not torn down" }
            }
        }
    }

    // --- 4. conclusion --------------------------------------------------------------------------------------------

    private suspend fun conclude(
        run: RunState,
        board: AgentBoard,
        tasks: TaskBoard,
    ): RunSummary {
        run.abortedBecause?.let { reason ->
            safely(run, "abort record") { recordAbort(run, reason, board) }
        }
        safely(run, "roll call") { rollCall(run) }
        safely(run, "steps nobody ran") { recordUncovered(run, board) }
        tasks.closeOpen(run.abortedBecause?.let { "run aborted: $it" } ?: "not run")
        safely(run, "network observation") { recordNetworkObservations(run) }
        safely(run, "company registration") {
            registerCompany(run)
            discoverCompany(run)
        }
        if (!run.options.keepData) safely(run, "teardown") { tearDown(run, board) }
        closeSessions(run)
        if (run.browserStarted) safely(run, "browser stop") { browser.stop() }
        val outcome = run.outcome()
        val endedAt = clock.now()
        safely(run, "run record") { runs.finish(run.runId, outcome.toRunResult(), endedAt.wall) }
        val report =
            try {
                finalizer.finalize(run.runId)
            } catch (e: Exception) {
                logger.error(e) { "run ${run.runId}: the report could not be written" }
                null
            }
        run.identities
            .filterNot { run.isFailed(it.agentId) }
            .forEach { board.update(it.agentId, AgentState.DONE, null, null) }
        val summary =
            RunSummary(
                runId = run.runId,
                outcome = outcome,
                stepsPassed = run.tally.stepsPassed,
                stepsFailed = run.tally.stepsFailed,
                assertionsFailed = run.tally.assertionsFailed,
                failedAgents = run.tally.failedAgents,
                reportDirectory = report?.toString(),
                durationMs = run.startedAt.elapsedUntil(endedAt).inWholeMilliseconds,
                assertionsInconclusive = run.tally.assertionsInconclusive,
            )
        board.finished(summary)
        return summary
    }

    /**
     * Why the run stopped and which steps never began: per wave when the run has waves (`wave 4: read, approve`), since
     * a step that began in the first wave may never have begun in the fourth.
     */
    private suspend fun recordAbort(
        run: RunState,
        reason: String,
        board: AgentBoard,
    ) {
        val notRun =
            if (run.passes.isEmpty()) {
                // Stopped before the passes were planned (the identities could not be made): nothing began.
                listOf(run.campaign.allSteps.joinToString(", ") { it.id })
            } else {
                run.passes.mapNotNull { pass ->
                    val steps = pass.steps.map { it.step.id }.filter { run.chosenIn(pass.number, it) == null }
                    when {
                        steps.isEmpty() -> null
                        pass.label == null -> steps.joinToString(", ")
                        else -> "${pass.label}: ${steps.joinToString(", ")}"
                    }
                }
            }.filter { it.isNotEmpty() }
        val detail = "run aborted: $reason; steps not run: ${notRun.joinToString("; ").ifEmpty { "-" }}"
        evidence.system(run, null, ABORT_ACTION, StepStatus.SKIPPED, detail)
        board.message(detail)
    }

    /**
     * The roll call: every tester each planned pass had for a step (the actors it started with, or would have started
     * with when it never began, and the testers out since an earlier failure) that has no final record of its own for
     * it gets a `not_reached` record saying why, so nobody planned is missing from the evidence ([NotReached]). A gap
     * in a run that went on to its end is Pətək's own and counts as a failed step (with no agent).
     *
     * Who was out is read when the step began: a step that began skipped its planned testers out by then with a record
     * of their own, and a tester that failed only later was never planned for it (with `n`, its place went to the next
     * tester). Only a step that never began names, at the end, the planned testers out by then.
     */
    private suspend fun rollCall(run: RunState) {
        val aborted = run.abortedBecause
        for (pass in run.passes) {
            for (planned in pass.steps) {
                val stepId = planned.step.id
                val chosen = run.chosenIn(pass.number, stepId)
                val acting =
                    chosen ?: actors.resolve(planned.step.actors, planned.pool.filterNot { run.isFailed(it.agentId) }).map { it.agentId }
                val failed =
                    if (chosen != null) {
                        emptyList()
                    } else {
                        actors.resolve(planned.step.actors, planned.pool).map { it.agentId }.filter(run::isFailed)
                    }
                (acting + failed)
                    .distinct()
                    .sorted()
                    .filterNot { run.isSettled(pass.number, stepId, it) }
                    .forEach { agentId ->
                        val why =
                            when {
                                agentId !in acting -> {
                                    "${NotReached.FAILED_EARLIER}: ${run.failureReason(agentId) ?: "failed"}"
                                }

                                chosen == null && pass.wave != null && !run.hasBegun(pass.number) -> {
                                    "${NotReached.WAVE_NOT_STARTED}: wave ${pass.wave} of ${pass.waves} never began; run aborted: $aborted"
                                }

                                aborted != null -> {
                                    "${NotReached.RUN_ABORTED}: $aborted"
                                }

                                else -> {
                                    "${NotReached.NEVER_REACHED}: the run went on, but $agentId has no record of step '$stepId'"
                                }
                            }
                        evidence.system(run, agentId, NOT_REACHED_ACTION, StepStatus.SKIPPED, why, stepId)
                        if (aborted == null && agentId in acting) {
                            logger.warn { "run ${run.runId}: $agentId has no record of step '$stepId' in pass ${pass.number}" }
                            run.tally.step(Tally.FAIL, null)
                        }
                    }
            }
        }
    }

    /**
     * A step that resolved to nobody in every execution it had (each wave, the run without waves, the swap) was done by
     * nobody: an agent-less `uncovered` record that counts as a failed step, its checks recorded as not evaluated. FAILED,
     * not inconclusive: no check ran at all, as with a wave's `not_covered` receivers (Faza 24.7), and the scenario or
     * the run's testers must change for it to be done; the `not_covered` key keeps it off the site and the testers.
     */
    private suspend fun recordUncovered(
        run: RunState,
        board: AgentBoard,
    ) {
        val waves = run.passes.count { it.wave != null }
        for (step in run.campaign.allSteps) {
            val executions = run.executionsOf(step.id)
            if (executions.isEmpty() || executions.any { it.isNotEmpty() }) continue
            // Out when it began, by role, department and registration: with `n`, a tester out before shifts the n-th one.
            val out = run.outOf(step.id)
            val detail =
                buildString {
                    append("$NOT_COVERED: no tester matched '${step.actors.raw}'")
                    append(if (waves > 0) " in any wave it ran in (${executions.size} of $waves)" else " in the run")
                    append("; nobody ran this step")
                    if (out.isNotEmpty()) {
                        append("; its testers were out after failing earlier: ")
                        append(out.joinToString(", ") { "${it.value} (${run.failureReason(it) ?: "failed"})" })
                    }
                }
            val stepId = evidence.system(run, null, UNCOVERED_ACTION, StepStatus.FAILED, detail, step.id, tally = Tally.FAIL)
            evidence.skippedAssertions(run, stepId, step.id, null, step.assertions, "not evaluated: nobody ran the step")
            board.message("step '${step.id}' was run by nobody: $detail")
        }
    }

    /** One record per agent (of [only], when given); reporting reads the comma-separated transports from `detail`. */
    private suspend fun recordNetworkObservations(
        run: RunState,
        only: Set<AgentId>? = null,
    ) {
        run.sessions.entries.filter { only == null || it.key in only }.sortedBy { it.key }.forEach { (agentId, session) ->
            safely(run, "network observation of $agentId") {
                val observation = session.networkObservation()
                val transports =
                    observation.transports
                        .map { it.name }
                        .sorted()
                        .joinToString(",")
                evidence.system(run, agentId, NETWORK_OBSERVATION, StepStatus.PASSED, transports)
            }
        }
    }

    private suspend fun tearDown(
        run: RunState,
        board: AgentBoard,
    ) {
        val result = teardown.teardown(run.runId)
        result.removed.forEach { evidence.system(run, null, "teardown $it", StepStatus.PASSED, "removed") }
        result.failures.forEach {
            evidence.system(run, null, "teardown", StepStatus.ERROR, it)
            board.message("teardown failed: $it")
        }
    }

    /** Closes the browser sessions of the run (of [only], when given). */
    private suspend fun closeSessions(
        run: RunState,
        only: Set<AgentId>? = null,
    ) {
        coroutineScope {
            run.sessions.filterKeys { only == null || it in only }.forEach { (agentId, session) ->
                async { safely(run, "closing the session of $agentId") { session.close() } }
            }
        }
    }

    private inline fun safely(
        run: RunState,
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn(e) { "run ${run.runId}: $what failed" }
        }
    }

    private fun RunOutcome.toRunResult(): RunResult =
        when (this) {
            RunOutcome.PASSED -> RunResult.PASSED
            RunOutcome.FAILED -> RunResult.FAILED
            RunOutcome.ABORTED -> RunResult.ABORTED
        }

    companion object {
        /** `action` of the SYSTEM step that carries a session's detected real-time transports. */
        const val NETWORK_OBSERVATION = "network_observation"

        /**
         * `action` of the SYSTEM step that records a tester's browser context restored after a crash. Detail:
         * `browser context lost (<reason>); restored (<n>) with the same identity, <state>[, back on <url>]`, where
         * `<state>` is [STORAGE_STATE_LOADED] or [NO_STORAGE_STATE].
         */
        const val RESTORE_SESSION = "restore_session"

        /** The restored context was opened with the storage state the tester saved when it signed in. */
        const val STORAGE_STATE_LOADED = "storage state loaded"

        /** The tester had saved no storage state yet (it had not signed in), so the restored context starts signed out. */
        const val NO_STORAGE_STATE = "no storage state loaded (none saved yet)"

        /** Suffix of the scenario steps run again after the account swap. */
        const val SWAP_SUFFIX = "@swap"

        /** A `wait_for` step no receiver could wait for in any wave: its check was never made (Faza 24.7). */
        const val NOT_COVERED = "not_covered"

        /** Leading keys of the `capacity` record's detail (see [CAPACITY_ACTION]). */
        const val WITHIN_CAPACITY = "within_capacity"
        const val OVER_CAPACITY = "over_capacity"
    }
}
