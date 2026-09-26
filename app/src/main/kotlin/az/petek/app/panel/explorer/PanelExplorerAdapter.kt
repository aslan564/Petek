/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.explorer

import az.petek.app.di.AppContainer
import az.petek.app.panel.PanelTargets
import az.petek.campaign.domain.Tenant
import az.petek.core.ids.ArtifactId
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelExplorer
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.SiteModelDiffView
import az.petek.evidence.domain.ArtifactRecord
import az.petek.explorer.application.ExploreSiteUseCase
import az.petek.explorer.application.ExplorerSettings
import az.petek.explorer.application.ScenarioRequest
import az.petek.explorer.domain.ExplorationBudget
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationObserver
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRecord
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationStatus
import az.petek.ownership.domain.OwnershipStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * The "Kəşfiyyat" screen's backend: runs the explorer agent ([ExploreSiteUseCase]) on the site of the owner's
 * instructions, one exploration at a time, in the background, and shows it live.
 *
 * - **Target.** The "Hədəf sayt" of the instruction form, checked with the target policy first (production hosts only
 *   with `PETEK_ALLOW_PRODUCTION=true`); a refusal names the field with the reason.
 * - **Phases.** ANONYMOUS always (read-only: the explorer cannot click, type or submit there). ROLE_BASED and
 *   TRIAL_TOUCH need logged-in sessions from [roleSessions]; without them the explorer skips them and the screen says
 *   why. TRIAL_TOUCH also needs the owner's "Sınaq toxunuşu" and a target confirmed as test data (the check of the role
 *   sessions, through the test API's `is_test`).
 * - **Browser.** The container's own explorer engine, started for the exploration and stopped after it, so runs and
 *   explorations never share (or stop) each other's browsers.
 * - **Live view.** Every event of the explorer updates an [ExplorationTracker]; [explorationUpdates] carries the new
 *   view at once. When the exploration ends, the stored site model replaces the live one, with its test ideas and a
 *   draft preview composed by the scenario generator (nothing is stored until the owner asks for the draft).
 * - **Answers.** [answerUnknown] stores the owner's answer in the [answers] book: it is shown with the instructions at
 *   once and grounds every later exploration of the same site and the scenario drafts.
 * - **After a restart** the latest stored exploration is shown again, replayed from its event log.
 *
 * Thread-safe: the tracker is only touched under one lock. Long work runs in [scope].
 */
internal class PanelExplorerAdapter(
    private val container: AppContainer,
    private val roleSessions: RoleSessionSource,
    private val answers: AnswerBook,
    private val scope: CoroutineScope,
    private val settings: ExplorerSettings = ExplorerSettings(language = container.config.language),
) : PanelExplorer {
    /** The exploration on screen: the owner's request and its tracker. [instructions] is null for a replayed one. */
    private class Current(
        val target: URI,
        val instructions: PanelInstructions?,
        val budget: ExplorationBudget,
        val tracker: ExplorationTracker,
    )

    /** What a scenario draft is generated from: the latest exploration with a stored site model. */
    data class DraftSource(
        val explorationId: ExplorationId,
        val target: URI,
        val grounding: String?,
        val departments: List<String>,
    )

    private val lock = Any()
    private var current: Current? = null
    private var job: Job? = null
    private val views = MutableStateFlow<ExplorationView?>(null)

    override val explorationUpdates: Flow<ExplorationView> = views.filterNotNull()

    init {
        scope.launch { restoreLatest() }
    }

    override fun exploration(): ExplorationView? = synchronized(lock) { current?.tracker?.view(now()) }

    override suspend fun startExploration(instructions: PanelInstructions): ExplorationView {
        val problems = instructions.problems()
        if (problems.isNotEmpty()) throw PanelRequestException(problems)
        val target = PanelTargets.allowed(instructions.target, container.config.targetPolicy, PanelInstructions.TARGET)
        val own = instructions.instructions.trim()
        if (own.length > ExplorationRequest.MAX_INSTRUCTION_CHARS) {
            throw PanelRequestException(
                listOf(
                    FieldProblem(
                        PanelInstructions.INSTRUCTIONS,
                        "Kəşfiyyat üçün təlimat ən çox ${ExplorationRequest.MAX_INSTRUCTION_CHARS} simvol ola bilər (indi ${own.length}).",
                    ),
                ),
            )
        }
        PanelTargets.reachable(target, container.reachability, PanelInstructions.TARGET)
        val grounding = answers.grounding(target, own, ExplorationRequest.MAX_INSTRUCTION_CHARS)
        val budget =
            ExplorationBudget(
                maxPages = instructions.budget.maxPages.coerceIn(1, ExplorationBudget.MAX_PAGES),
                maxMinutes = instructions.budget.maxMinutes.coerceIn(1, ExplorationBudget.MAX_MINUTES),
            )
        val previous = container.explorations.latestVersion(target).takeIf { it > 0 }
        return synchronized(lock) {
            if (job?.isActive == true) throw PanelConflictException("Artıq bir kəşfiyyat gedir. Bitməsini gözləyin və ya dayandırın.")
            val tracker = ExplorationTracker(target.toString(), grounding, instructions.budget, now(), previous)
            val started = Current(target, instructions, budget, tracker)
            current = started
            publish()
            job = scope.launch { explore(started) }
            tracker.view(now())
        }
    }

    override suspend fun answerUnknown(
        unknownId: String,
        answer: String,
    ): ExplorationView {
        val text = answer.trim()
        if (text.isEmpty()) throw PanelRequestException(listOf(FieldProblem("answer", "Cavab boş ola bilməz.")))
        val shown = synchronized(lock) { current } ?: throw PanelNotFoundException("Kəşfiyyat yoxdur.")
        val (unknown, explorationId) =
            synchronized(lock) { shown.tracker.unknown(unknownId) to shown.tracker.explorationId }
        if (unknown == null || explorationId == null) throw PanelNotFoundException("Kəşfiyyatçının belə sualı yoxdur.")
        withContext(Dispatchers.IO) { answers.record(shown.target, explorationId.value, unknownId, unknown.question, text, now().wall) }
        val own = shown.instructions?.instructions ?: ownInstructions(synchronized(lock) { shown.tracker.grounding })
        val grounding = answers.grounding(shown.target, own, ExplorationRequest.MAX_INSTRUCTION_CHARS)
        return synchronized(lock) {
            shown.tracker.answered(unknownId, text, grounding)
            if (current === shown) publish()
            shown.tracker.view(now())
        }
    }

    override suspend fun cancelExploration(): Boolean {
        val running = synchronized(lock) { job?.takeIf { it.isActive } } ?: return false
        running.cancel()
        return true
    }

    override suspend fun compareWithPrevious(): SiteModelDiffView? {
        val target = synchronized(lock) { current?.target } ?: return null
        return container.compareExplorations.latest(target)?.let(ExplorerViews::diff)
    }

    /** Any recorded capture of an exploration (screenshots of visited pages, DOM snapshots); null for anything else. */
    override suspend fun explorationArtifact(artifactId: ArtifactId): ArtifactRecord? = container.explorations.artifact(artifactId)

    // --- for the scenario screen ----------------------------------------------------------------------------------

    /**
     * The exploration a draft is generated from: the one on screen when it saved a model, else the latest stored one
     * that did. Throws [PanelConflictException] while an exploration is still going, or when none saved a model.
     */
    suspend fun draftSource(): DraftSource {
        val shown = synchronized(lock) { current }
        if (shown != null && synchronized(lock) { shown.tracker.running }) {
            throw PanelConflictException("Kəşfiyyat hələ gedir; ssenari layihəsi o bitəndən sonra sayt modelindən yaranır.")
        }
        val id = shown?.let { synchronized(lock) { it.tracker.explorationId } }
        if (shown != null && id != null && container.explorations.model(id) != null) {
            return DraftSource(id, shown.target, synchronized(lock) { shown.tracker.grounding }, shown.instructions?.departments.orEmpty())
        }
        val stored =
            container.explorations.list(limit = LOOKBACK).firstOrNull { it.modelVersion != null }
                ?: throw PanelConflictException("Əvvəlcə saytı kəşf edin: ssenari layihəsi son kəşfiyyatın sayt modelindən yaranır.")
        return DraftSource(stored.id, stored.request.target, stored.request.grounding, emptyList())
    }

    /** Receives the events a draft generation adds to [explorationId]'s log (DraftReady), when it is on screen. */
    fun observerFor(explorationId: ExplorationId): ExplorationObserver =
        ExplorationObserver { event ->
            synchronized(lock) {
                val shown = current?.takeIf { it.tracker.explorationId == explorationId } ?: return@ExplorationObserver
                shown.tracker.apply(event)
                publish()
            }
        }

    /** The draft stored for the owner replaces the preview on the screen. */
    fun drafted(
        explorationId: ExplorationId,
        yaml: String,
    ) = synchronized(lock) {
        val shown = current?.takeIf { it.tracker.explorationId == explorationId } ?: return@synchronized
        shown.tracker.drafted(yaml)
        publish()
    }

    /** Whether drafts for [target] may use the target's test API (ids and oracle checks). */
    fun testApi(target: URI): Boolean = container.oracle.isAvailable && PanelTargets.sameSite(target, container.config.target)

    /**
     * Whether drafts for [target] are for a site with companies: its profile's `tenant`, else companies when the test
     * API can seed one (the KadroHR shape), else none (Faza 13).
     */
    fun tenant(target: URI): Tenant =
        container.config
            .profileFor(target)
            ?.spec
            ?.tenant ?: if (testApi(target)) Tenant.COMPANY else Tenant.NONE

    // --- the exploration ------------------------------------------------------------------------------------------

    private suspend fun explore(run: Current) {
        val engine = container.explorerBrowserEngine
        var sessions: RoleSessions? = null
        try {
            val factory = engine.start(container.browserConfig())
            val request = run.instructions
            // Logged-in sessions and trial touches write to the site: only on one whose owner proved it (ADR-0012).
            val ownership = container.ownership.check(run.target)
            val writable = ownership.allowsWrites
            sessions =
                if (ownership is OwnershipStatus.Unverified) {
                    RoleSessions.none(PanelTargets.readOnlyExploration(ownership))
                } else {
                    roleSessions.open(
                        RoleSessionRequest(run.target, request?.allowWrites == true, request?.departments.orEmpty()),
                        factory,
                    ) { line -> note(run, ExplorationTracker.SESSIONS, line) }
                }
            sessions.note?.let { note(run, ExplorationTracker.SESSIONS, it) }
            val explorer =
                ExploreSiteUseCase(
                    sessions = factory,
                    llm = container.llm,
                    artifacts = container.artifacts,
                    repository = container.explorations,
                    clock = container.clock,
                    ids = container.ids,
                    targetPolicy = container.config.targetPolicy,
                    testTargetCheck = sessions.testCheck,
                    settings = settings,
                )
            val grounding = synchronized(lock) { run.tracker.grounding }
            val result =
                explorer.execute(
                    ExplorationRequest(
                        run.target,
                        grounding,
                        run.budget,
                        ExplorationPhase.entries.toSet(),
                        request?.allowWrites == true && writable,
                    ),
                    sessions.sessions,
                ) { event -> onEvent(run, event) }
            conclude(run, result.record.id)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val id = synchronized(lock) { run.tracker.explorationId }
                if (id == null) update(run) { it.cancelled(now()) } else conclude(run, id)
            }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "The exploration of ${run.target} failed" }
            withContext(NonCancellable) {
                update(run) { it.failed(now(), reason(e)) }
                synchronized(lock) { run.tracker.explorationId }?.let { conclude(run, it) }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    sessions?.close()
                } catch (e: Exception) {
                    logger.warn(e) { "The explorer's role sessions could not be released" }
                }
                try {
                    engine.stop()
                } catch (e: Exception) {
                    logger.warn(e) { "The explorer's browser engine did not stop cleanly" }
                }
            }
        }
    }

    private fun onEvent(
        run: Current,
        event: ExplorationEvent,
    ) {
        val prefilled =
            (event as? ExplorationEvent.UnknownRaised)?.let {
                answers.answerFor(run.target, it.explorationId.value, it.unknown.id, it.unknown.question)
            }
        update(run) { tracker ->
            tracker.apply(event)
            val unknown = (event as? ExplorationEvent.UnknownRaised)?.unknown
            if (unknown != null && prefilled != null) tracker.answered(unknown.id, prefilled, tracker.grounding)
        }
    }

    /** Loads the saved model of [id], its test ideas and a draft (the stored one, else a preview) onto the screen. */
    private suspend fun conclude(
        run: Current,
        id: ExplorationId,
    ) {
        val model =
            try {
                container.explorations.model(id)
            } catch (e: Exception) {
                logger.warn(e) { "The site model of exploration $id could not be loaded" }
                null
            }
        val grounding = synchronized(lock) { run.tracker.grounding }
        val ideas = model?.let { ExplorerViews.ideas(it, grounding) }.orEmpty()
        val stored =
            runCatching {
                container.explorations
                    .drafts(id)
                    .lastOrNull()
                    ?.yaml
            }.getOrNull()
        val draft =
            stored ?: model?.let {
                try {
                    container
                        .scenarioGenerator(DraftSettings.of(run.instructions?.departments.orEmpty()))
                        .compose(
                            it,
                            ScenarioRequest(id, grounding.ifBlank { null }, testApi = testApi(run.target), tenant = tenant(run.target)),
                        ).yaml
                } catch (e: Exception) {
                    logger.warn(e) { "No scenario draft could be composed from exploration $id" }
                    note(run, ExplorationTracker.NOTE, "Ssenari layihəsi hazırlana bilmədi: ${reason(e)}")
                    null
                }
            }
        update(run) { it.finished(model, ideas, draft) }
    }

    // --- after a restart ------------------------------------------------------------------------------------------

    private suspend fun restoreLatest() {
        try {
            val record = container.explorations.list(limit = 1).firstOrNull() ?: return
            val replayed = replay(record)
            val shown =
                synchronized(lock) {
                    if (current != null) return
                    current = replayed
                    publish()
                    replayed
                }
            record.modelVersion?.let { conclude(shown, record.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "The latest exploration could not be shown again" }
        }
    }

    private suspend fun replay(record: ExplorationRecord): Current {
        val target = record.request.target
        val saved = record.modelVersion
        val previous =
            container.explorations
                .versions(target)
                .filter { saved == null || it < saved }
                .maxOrNull()
        val now = now()
        val started =
            HarnessTimestamp(
                record.startedAt,
                now.monotonicNanos - (now.wall.toEpochMilli() - record.startedAt.toEpochMilli()) * NANOS_PER_MILLI,
            )
        val budget = record.request.budget
        val tracker =
            ExplorationTracker(
                target.toString(),
                record.request.grounding.orEmpty(),
                PanelBudget(budget.maxMinutes, DEFAULT_STEPS_PER_AGENT, budget.maxPages),
                started,
                previous,
            )
        container.explorations.events(record.id).forEach { event ->
            tracker.apply(event)
            if (event is ExplorationEvent.UnknownRaised) {
                answers.answerFor(target, record.id.value, event.unknown.id, event.unknown.question)?.let {
                    tracker.answered(event.unknown.id, it, tracker.grounding)
                }
            }
        }
        if (record.status == ExplorationStatus.RUNNING) tracker.interrupted(record.endedAt)
        return Current(target, null, budget, tracker)
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun note(
        run: Current,
        kind: String,
        text: String,
    ) = update(run) { it.note(now().wall, kind, text) }

    private inline fun update(
        run: Current,
        change: (ExplorationTracker) -> Unit,
    ) = synchronized(lock) {
        change(run.tracker)
        if (current === run) publish()
    }

    /** Must be called under [lock]. */
    private fun publish() {
        views.value = current?.tracker?.view(now())
    }

    private fun now(): HarnessTimestamp = container.clock.now()

    private fun reason(error: Exception): String = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()

    /** The owner's own part of a grounding text (the answers block is added again from the book). */
    private fun ownInstructions(grounding: String): String = grounding.substringBefore(AnswerBook.HEADING).trim()

    private companion object {
        const val LOOKBACK = 20
        const val DEFAULT_STEPS_PER_AGENT = 40
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
