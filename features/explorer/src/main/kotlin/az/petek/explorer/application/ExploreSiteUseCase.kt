/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.application

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.core.error.PetekException
import az.petek.core.ids.IdGenerator
import az.petek.core.security.TargetPolicy
import az.petek.core.security.TargetVerdict
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.ArtifactStore
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationObserver
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRecord
import az.petek.explorer.domain.ExplorationRepository
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationResult
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.SiteModelAccumulator
import az.petek.explorer.domain.TestTargetCheck
import az.petek.explorer.domain.TestTargetVerdict
import az.petek.llm.domain.LlmClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

/** The target was refused before anything was stored or opened (e.g. a production host, CLAUDE.md rule 8). */
class ExplorationRefusedException(
    message: String,
) : PetekException(message)

/**
 * Explores a site and learns its model (docs/PLAN.md Faza 6). Phases run in the order ANONYMOUS, ROLE_BASED,
 * TRIAL_TOUCH, each only when requested:
 *
 * - **ANONYMOUS** opens its own browser session (label [ExplorerSettings.sessionLabel]) and crawls without logging in.
 * - **ROLE_BASED** crawls again with each logged-in session in `roleSessions` (role name -> session; the caller logs
 *   them in and keeps owning them). Comparing what each role reached and was offered fills `reachableBy` and infers
 *   `forbiddenRoles`.
 * - **TRIAL_TOUCH** runs only with [ExplorationRequest.allowWrites], a [TestTargetCheck] confirmation *and* logged-in
 *   role sessions (only they write into the test company that teardown removes); otherwise it is skipped with the
 *   reason. It is the only phase that submits anything (see [TrialToucher]); the anonymous session only watches.
 *
 * Every crawl looks through a [ReadOnlyBrowserSession], so outside TRIAL_TOUCH the explorer cannot click, type or
 * leave the target's origin. The target must pass [targetPolicy] first, else [ExplorationRefusedException] is thrown
 * and nothing is stored. Events go to the repository and then to the observer as they happen. The time budget is
 * honoured by the harness clock between pages and by a hard timeout; whatever the ending (done, timeout,
 * cancellation, error), the model and summary are saved: model version = previous version for the same target + 1,
 * marked [SiteModel.partial] unless the exploration completed without leaving pages unvisited at the page budget
 * (so a version diff never reports a page it simply did not reach as removed). A cancelled exploration is saved as
 * CANCELLED and the cancellation is rethrown.
 */
class ExploreSiteUseCase(
    private val sessions: BrowserSessionFactory,
    private val llm: LlmClient,
    private val artifacts: ArtifactStore,
    private val repository: ExplorationRepository,
    private val clock: HarnessClock,
    private val ids: IdGenerator,
    private val targetPolicy: TargetPolicy,
    private val testTargetCheck: TestTargetCheck = TestTargetCheck.REFUSE_ALL,
    private val settings: ExplorerSettings = ExplorerSettings(),
) {
    suspend fun execute(
        request: ExplorationRequest,
        roleSessions: Map<String, BrowserSession> = emptyMap(),
        observer: ExplorationObserver = ExplorationObserver.NONE,
    ): ExplorationResult {
        val verdict = targetPolicy.verify(request.target)
        if (verdict is TargetVerdict.Refused) throw ExplorationRefusedException(verdict.reason)
        requireValidRoles(roleSessions.keys)
        val id = ExplorationId.from(ids.runId())
        val started = clock.now()
        repository.create(ExplorationRecord(id, request, ExplorationStatus.RUNNING, started.wall))
        val emitter = ExplorationEmitter(id, repository, observer, clock)
        val context =
            ExplorationContext(
                id = id,
                request = request,
                settings = settings,
                accumulator = SiteModelAccumulator(request.target),
                emitter = emitter,
                analyst = PageAnalyst(llm, settings, id),
                capture = PageCapture(artifacts, repository, clock, ids, id, settings.pageSettleTimeout, settings.pageSettlePoll),
                findings = FindingRecorder(id, repository, emitter, ids),
                clock = clock,
                startedAt = started,
            )
        logger.info { "Exploration $id of ${request.target} started (phases ${request.phases.sorted()})" }
        emitter.emit {
            ExplorationEvent.Started(it, request.target, request.phases.sorted(), request.grounding, request.budget, request.allowWrites)
        }
        val phases = Phases(context, roleSessions)
        var status = ExplorationStatus.COMPLETED
        var failure: Exception? = null
        try {
            val completed = withTimeoutOrNull(request.budget.timeLimit) { phases.run() }
            // timedOut is set when a budget check stopped work early; a run that simply finished late is complete.
            if (completed == null || context.timedOut) status = ExplorationStatus.TIMED_OUT
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                phases.close()
                finish(context, ExplorationStatus.CANCELLED, null)
            }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Exploration $id failed" }
            status = ExplorationStatus.FAILED
            failure = e
        }
        return withContext(NonCancellable) {
            phases.close()
            finish(context, status, failure)
        }
    }

    private fun requireValidRoles(roles: Set<String>) {
        require(roles.size <= settings.maxRoles) { "At most ${settings.maxRoles} role sessions can be explored, got ${roles.size}" }
        roles.forEach { role ->
            require(ROLE_NAME.matches(role)) { "Role name '$role' must be lower-case letters, digits, '-' or '_' (at most 40)" }
            require(
                role != SiteModelAccumulator.ANONYMOUS,
            ) { "'${SiteModelAccumulator.ANONYMOUS}' is the name of the crawl without a session" }
        }
    }

    private suspend fun finish(
        context: ExplorationContext,
        status: ExplorationStatus,
        failure: Exception?,
    ): ExplorationResult {
        val model = saveModel(context, partial = status != ExplorationStatus.COMPLETED || context.pageBudgetReached)
        val summary = context.summary(status)
        val endedAt = clock.now().wall
        repository.finish(context.id, status, endedAt, summary, model.version)
        if (failure != null) {
            val reason = failure.message ?: failure::class.simpleName.orEmpty()
            context.emitter.emit { ExplorationEvent.Failed(it, reason, model.version) }
        } else {
            context.emitter.emit { ExplorationEvent.Finished(it, summary, model.version) }
        }
        logger.info { "Exploration ${context.id} ended $status: ${summary.counts}, model v${model.version}" }
        val record = ExplorationRecord(context.id, context.request, status, context.startedAt.wall, endedAt, summary, model.version)
        return ExplorationResult(record, model, context.findings.all)
    }

    /** Saves the model as the next version of its target; retried when another exploration took that version meanwhile. */
    private suspend fun saveModel(
        context: ExplorationContext,
        partial: Boolean,
    ): SiteModel {
        var attempt = 0
        while (true) {
            val version = repository.latestVersion(context.request.target) + 1
            val model = context.accumulator.build(version, context.id, clock.now().wall, partial)
            try {
                repository.saveModel(model)
                return model
            } catch (e: IllegalArgumentException) {
                if (++attempt >= SAVE_ATTEMPTS) throw e
                logger.warn { "Site model version $version of ${context.request.target} was taken; retrying" }
            }
        }
    }

    /** Runs the requested phases in order and owns the anonymous session. */
    private inner class Phases(
        private val context: ExplorationContext,
        private val roleSessions: Map<String, BrowserSession>,
    ) {
        private var anonymousSession: BrowserSession? = null

        suspend fun run(): Boolean {
            for (phase in context.request.phases.sorted()) {
                if (context.deadlinePassed()) break
                when (phase) {
                    ExplorationPhase.ANONYMOUS -> anonymous()
                    ExplorationPhase.ROLE_BASED -> roleBased()
                    ExplorationPhase.TRIAL_TOUCH -> trialTouch()
                }
            }
            return true
        }

        private suspend fun anonymous() {
            val session = sessions.open(SessionOptions(label = settings.sessionLabel, baseUrl = context.request.target))
            anonymousSession = session
            started(ExplorationPhase.ANONYMOUS, listOf(SiteModelAccumulator.ANONYMOUS))
            CrawlPass(context, SiteModelAccumulator.ANONYMOUS, anonymous = true, ReadOnlyBrowserSession(session, context.origin)).run()
        }

        private suspend fun roleBased() {
            if (roleSessions.isEmpty()) return skipped(ExplorationPhase.ROLE_BASED, "no logged-in sessions were given")
            val roles = roleSessions.keys.sorted()
            started(ExplorationPhase.ROLE_BASED, roles)
            for (role in roles) {
                if (context.deadlinePassed()) return
                CrawlPass(context, role, anonymous = false, ReadOnlyBrowserSession(roleSessions.getValue(role), context.origin)).run()
            }
        }

        private suspend fun trialTouch() {
            if (!context.request.allowWrites) return skipped(ExplorationPhase.TRIAL_TOUCH, "allowWrites is false")
            // Only a logged-in role writes into the test company, which teardown removes as a whole; what a visitor
            // creates (a contact or demo request) belongs to no company and would stay on the target for good.
            if (roleSessions.isEmpty()) {
                return skipped(
                    ExplorationPhase.TRIAL_TOUCH,
                    "no logged-in session was given; visitors' writes are not part of the test company and cannot be torn down",
                )
            }
            val watchers =
                buildMap {
                    putAll(roleSessions)
                    anonymousSession?.let { put(SiteModelAccumulator.ANONYMOUS, it) }
                }
            when (val check = testTargetCheck.check(context.request.target)) {
                is TestTargetVerdict.Refused -> {
                    return skipped(ExplorationPhase.TRIAL_TOUCH, "the target is not confirmed as test data: ${check.reason}")
                }

                is TestTargetVerdict.Confirmed -> {
                    context.notes += "Trial touch allowed: ${check.evidence}"
                }
            }
            started(ExplorationPhase.TRIAL_TOUCH, roleSessions.keys.sorted())
            TrialToucher(context, roleSessions, watchers, clock).run()
        }

        private suspend fun started(
            phase: ExplorationPhase,
            roles: List<String>,
        ) {
            context.phasesRun += phase
            context.emitter.emit { ExplorationEvent.PhaseStarted(it, phase, roles) }
        }

        private suspend fun skipped(
            phase: ExplorationPhase,
            reason: String,
        ) {
            context.phasesSkipped[phase] = reason
            context.emitter.emit { ExplorationEvent.PhaseSkipped(it, phase, reason) }
        }

        /** Closes the session this exploration opened itself; the callers' role sessions stay open. */
        suspend fun close() {
            val session = anonymousSession ?: return
            anonymousSession = null
            try {
                session.close()
            } catch (e: Exception) {
                logger.warn(e) { "Closing the explorer's browser session failed" }
            }
        }
    }

    private companion object {
        val ROLE_NAME = Regex("[a-z][a-z0-9_-]{0,39}")
        const val SAVE_ATTEMPTS = 3
    }
}
