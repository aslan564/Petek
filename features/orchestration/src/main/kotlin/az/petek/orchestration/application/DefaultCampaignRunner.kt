/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.agent.application.TesterAgentFactory
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.AgentVariables
import az.petek.agent.domain.Colleague
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TemplateRenderer
import az.petek.core.ids.AgentId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResource
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepStatus
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

private val logger = KotlinLogging.logger {}

/**
 * Runs one campaign end to end (docs/ARCHITECTURE.md "Run lifecycle"):
 * 1. creates the run record and the identity registry (tag derived from the run id);
 * 2. starts the browser and gives every identity its own session and [az.petek.agent.application.TesterAgent];
 * 3. executes the setup steps, then the main steps, in order and within the campaign's time budget
 *    (see [StepExecutor] for what happens inside a step). In a `wait_for` step, `visible_text`/`latency_max` are
 *    checked as soon as the event arrives, before the receiver's own action, so t1 - t0 measures delivery rather
 *    than the agent; the other assertions run after the action;
 * 4. always — also on abort, budget timeout or cancellation — records the observed real-time transports, tears down
 *    the test company (unless `keepData`), closes sessions, stops the browser, finishes the run record and asks the
 *    [RunFinalizer] for the report.
 *
 * Setup semantics: an actor that fails a setup step is marked FAILED with the failure key and skipped (with a
 * SKIPPED record) in every later step; a successful sign-up/login step marks it ACTIVE
 * ([RunnerSettings.activatingRunFunctions], or any setup `do` step). If the admin fails a setup step nothing else
 * can work, so the run is ABORTED. `on_fail: abort` (step or campaign level) aborts after a failed step.
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
 * run function that waits for an e-mail but records its sub-steps). The run's [EventBus] comes from [busFactory]
 * (one per run); [sharedStateFactory] creates the run's [SharedRunState].
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
        val completed =
            withTimeoutOrNull(run.budget) {
                planIdentities(run)
                startAgents(run, board)
                runSteps(run, board, tasks)
                true
            }
        if (completed == null) {
            run.abort("time budget of ${run.campaign.settings.budget.maxMinutes} min exceeded")
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

    // --- 2. browser and agents ------------------------------------------------------------------------------------

    private suspend fun startAgents(
        run: RunState,
        board: AgentBoard,
    ) {
        run.browserStarted = true
        val factory = browser.start(browserConfig)
        // One roster for everyone: who the colleagues are, never their secrets (computed once, shared read-only).
        val colleagues = run.identities.map(Colleague::of)
        coroutineScope {
            run.identities
                .map { identity -> async(diagnostics.of(run.runId, identity.agentId)) { openAgent(run, factory, identity, colleagues) } }
                .awaitAll()
        }
        board.start(run.runId, run.identities)
        run.identities
            .filter { run.isFailed(it.agentId) }
            .forEach { board.update(it.agentId, AgentState.FAILED, null, "browser session could not be opened") }
    }

    private suspend fun openAgent(
        run: RunState,
        factory: BrowserSessionFactory,
        identity: Identity,
        colleagues: List<Colleague>,
    ) {
        val agentId = identity.agentId
        try {
            val options =
                SessionOptions(
                    label = agentId.value,
                    baseUrl = run.campaign.settings.target,
                    localStorage = run.campaign.target.localStorage,
                    correlationHeader = settings.correlationHeader,
                )
            val stored = storageStatePath(run.runId, agentId)
            val session =
                RestoringBrowserSession(
                    initial = factory.open(options),
                    // Same identity, and still signed in when it had signed in: its saved storage state (rule 7).
                    reopen = { factory.open(options.copy(storageState = stored.takeIf(Files::isRegularFile))) },
                    onRestored = { count, reason, url ->
                        val back = url?.let { ", back on $it" }.orEmpty()
                        evidence.system(
                            run,
                            agentId,
                            "restore_session",
                            StepStatus.PASSED,
                            "browser context lost ($reason); restored ($count) with the same identity and its storage state$back",
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
                )
            run.agents[agentId] = agents.create(runtime)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val reason = FailureReason.BROWSER_ERROR.key
            val detail = "$reason: ${e::class.simpleName}: ${e.message}"
            evidence.system(run, agentId, "open_session", StepStatus.ERROR, detail, tally = Tally.FAIL)
            markFailed(run, agentId, reason)
            if (identity.role == Role.ADMIN) run.abort("the admin's browser session could not be opened")
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
    ) {
        val executor = StepExecutor(run, services(board, tasks))
        for (step in run.campaign.allSteps) {
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
            if (failureKey != null) {
                markFailed(run, agentId, failureKey)
                board.update(agentId, AgentState.FAILED, step.id, "failed setup: $failureKey")
                board.message("$agentId failed setup step '${step.id}' ($failureKey) and is excluded from later steps")
                if (actor.identity.role == Role.ADMIN) run.abort("the admin failed setup step '${step.id}' ($failureKey)")
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
            )
        board.finished(summary)
        return summary
    }

    private suspend fun recordAbort(
        run: RunState,
        reason: String,
        board: AgentBoard,
    ) {
        val notRun =
            run.campaign.allSteps
                .map { it.id }
                .filterNot { it in run.startedSteps }
        val detail = "run aborted: $reason; steps not run: ${notRun.joinToString(", ").ifEmpty { "-" }}"
        evidence.system(run, null, "abort", StepStatus.SKIPPED, detail)
        board.message(detail)
    }

    /** One record per agent; reporting reads the comma-separated transports from `detail`. */
    private suspend fun recordNetworkObservations(run: RunState) {
        run.sessions.entries.sortedBy { it.key }.forEach { (agentId, session) ->
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

    private suspend fun closeSessions(run: RunState) {
        coroutineScope {
            run.sessions.forEach { (agentId, session) ->
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
    }
}
