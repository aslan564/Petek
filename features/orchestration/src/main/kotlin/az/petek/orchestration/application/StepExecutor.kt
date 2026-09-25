/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.agent.application.TesterAgent
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.Pacing
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TemplateContext
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer
import az.petek.campaign.domain.WaitForSpec
import az.petek.core.ids.AgentId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.ActorResolver
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.TaskState
import az.petek.verification.application.VerifyStepUseCase
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionInput
import az.petek.verification.domain.RaceEvidence
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/** What one actor achieved in one scenario step. [failureKey] is null when the actor passed the step. */
internal data class ActorStepResult(
    val identity: Identity,
    /** Null when the action never ran (awaited event missing, template error). */
    val outcome: ActionOutcome?,
    val failureKey: String?,
    /** In a race step (`only_one_succeeds`): what the actor's own requests showed; null otherwise or when it never acted. */
    val race: RaceEvidence? = null,
    /** The actor lost the race: expected, so it is not a failure ([failureKey] says whether anything else failed). */
    val lostRace: Boolean = false,
) {
    val failed: Boolean get() = failureKey != null
}

internal data class StepResult(
    val step: ScenarioStep,
    val actors: List<ActorStepResult>,
    val groupFailed: Boolean,
) {
    val failed: Boolean get() = groupFailed || actors.any { it.failed }
}

/**
 * Executes one scenario step for all its actors (docs/ARCHITECTURE.md "Run lifecycle", step 3). Per actor:
 * `wait_for` on the bus -> reception checks -> render templates -> (start barrier) -> (pacing) -> perform under the
 * watchdog -> `emits` -> the remaining assertions. All actors of a step run concurrently; `only_one_succeeds` is judged
 * once all of them are done. The campaign's pacing ([StartPacer]) staggers and limits the actions of a step that is
 * not `parallel` and has an action (`do` or `run`); a step that only asserts is not paced.
 *
 * Reception checks: in a step with `wait_for`, `visible_text` (and the `latency_max` that reads its latency) are
 * evaluated right after the event arrives, before the actor's own action. Their deadline is t0 + `within`, so
 * evaluating them after a multi-second LLM action would measure the agent instead of the target's delivery
 * (design decision 3: t1 - t0 is the real delivery latency). Every other assertion runs after the action.
 *
 * Races (`only_one_succeeds`): whether an actor succeeded is decided by code from the requests its own browser sent
 * during the action ([BrowserSession.mutations], judged by [RaceEvidence]), never by the agent's `done(success)` or
 * summary (CLAUDE.md rule 2). The session is read once right before the action's start time is taken (so answers to
 * earlier requests are timestamped before it) and once after the action. Only an actor whose requests succeeded
 * emits the step's event. An actor that lost the race — the target refused it with 409/422, or it gave its answer
 * (success, a reported problem or a refusal) without sending a matching request while another actor won — did what a
 * race expects: its action is recorded PASSED with detail `lost_race: ...`, it does not count as a failed agent, and
 * the group assertion decides. Any other refusal or error answer to its own request is no lost race; when its agent
 * claimed success anyway, the action is FAILED with `request_failed: ...`. Because all that needs every actor's
 * evidence, the action records of a race step are written once all its actors are done (with their own start and end
 * times); when the step is interrupted first (budget, abort), the racers that already acted are recorded unjudged.
 *
 * Every task transition (waiting, running, final state) and every event published or received is reported to the
 * [TaskBoard].
 */
internal class StepExecutor(
    private val run: RunState,
    private val services: StepServices,
) {
    private val resolver: ActorResolver get() = services.resolver
    private val clock: HarnessClock get() = services.clock
    private val ids: IdGenerator get() = services.ids
    private val evidence: HarnessEvidence get() = services.evidence
    private val board: AgentBoard get() = services.board
    private val tasks: TaskBoard get() = services.tasks

    suspend fun execute(step: ScenarioStep): StepResult {
        board.stepStarted(step.id)
        // What `{last_id}` means for an actor that neither waits for nor emits an event in this step: the newest object
        // before the step began, taken once, so concurrent actors never see each other's ids through it.
        val lastIdBeforeStep = run.bus.latestAny()?.objectId
        recordSkippedFailedActors(step)
        val chosen = resolver.resolve(step.actors, run.activeIdentities())
        run.executedActors[step.id] = chosen.map { it.agentId }
        if (chosen.isEmpty()) {
            evidence.system(run, null, "skip", StepStatus.SKIPPED, "no active actor matches '${step.actors.raw}'", step.id)
            return StepResult(step, emptyList(), groupFailed = false)
        }
        val race = raceSpec(step)
        val barrier = if (step.parallel) StartBarrier(chosen.size) else null
        // A step without an action only checks the page: nothing reaches the target that pacing would spread out.
        val pacing = if (step.parallel || step.action is StepAction.None) Pacing.NONE else run.campaign.settings.pacing
        val raced = ConcurrentLinkedQueue<ActorRun.Raced>()
        val runs =
            try {
                coroutineScope {
                    val pacer = StartPacer(this, pacing, chosen.map { it.agentId })
                    try {
                        chosen
                            .map { identity ->
                                async(services.diagnostics.of(run.runId, identity.agentId)) {
                                    runActor(step, identity, barrier, race, pacer, lastIdBeforeStep).also {
                                        if (it is ActorRun.Raced) {
                                            raced +=
                                                it
                                        }
                                    }
                                }
                            }.awaitAll()
                    } finally {
                        pacer.close()
                    }
                }
            } catch (e: Exception) {
                // Cancelled (budget, abort) or an actor crashed: racers that already acted still leave their records.
                withContext(NonCancellable) { settleUnjudged(raced, e) }
                throw e
            }
        val results = settle(runs)
        return StepResult(step, results, groupFailed = verifyGroup(step, results))
    }

    private suspend fun recordSkippedFailedActors(step: ScenarioStep) {
        resolver
            .resolve(step.actors, run.identities)
            .filter { run.isFailed(it.agentId) }
            .forEach {
                val reason = run.failureReason(it.agentId) ?: "failed"
                val detail = "agent failed earlier ($reason)"
                evidence.system(run, it.agentId, "skip", StepStatus.SKIPPED, detail, step.id)
                tasks.update(step.id, it.agentId, TaskState.SKIPPED, detail)
            }
    }

    private suspend fun runActor(
        step: ScenarioStep,
        identity: Identity,
        barrier: StartBarrier?,
        race: AssertionSpec.OnlyOneSucceeds?,
        pacer: StartPacer,
        lastIdBeforeStep: String?,
    ): ActorRun {
        var arrived = false
        try {
            val actor = ActorContext(step, identity, run.sessions.getValue(identity.agentId), run.agents.getValue(identity.agentId))
            val waited =
                step.waitFor?.let { spec ->
                    when (val result = awaitEvent(actor, spec)) {
                        is WaitResult.Received -> result.waited
                        is WaitResult.TimedOut -> return ActorRun.Settled(notReceived(actor, spec, result.startedAt))
                    }
                }
            val reception = waited?.let { receive(actor, it) }
            val templates = templateContext(identity, waited?.event?.objectId ?: lastIdBeforeStep)
            val action =
                try {
                    render(step.action, templates)
                } catch (e: TemplateException) {
                    return ActorRun.Settled(templateFailed(actor, e))
                }
            val raceStart = race?.let { startRace(actor) }
            if (barrier != null) {
                barrier.arrive()
                arrived = true
                barrier.awaitOpen()
            }
            val performed =
                pacer.paced(identity.agentId, { board.update(identity.agentId, AgentState.WAITING, step.id, it) }) {
                    perform(actor, action, templates)
                }
            val requests = if (race != null && raceStart != null) requestsOf(actor, race, raceStart) else null
            if (requests == null) recordAction(actor, performed, actionRecord(step, performed.outcome, race = null, lost = null))
            failureScreenshot(actor, performed.outcome)
            val succeeded = requests?.succeeded ?: performed.outcome.succeeded
            val emitted = if (succeeded) step.emits?.let { emit(actor, it, performed.outcome, templates) } else null
            val lastId = emitted?.event?.objectId ?: waited?.event?.objectId ?: lastIdBeforeStep
            val checks =
                verifyActor(actor, actor.stepId, afterActionSpecs(step), templateContext(identity, lastId), waited?.event?.t0)
            val acted = Acted(actor, performed, requests, emitted, listOfNotNull(reception, checks))
            return if (requests == null) ActorRun.Settled(conclude(acted, lost = null)) else ActorRun.Raced(acted)
        } finally {
            if (barrier != null && !arrived) barrier.arrive()
        }
    }

    // --- wait_for -------------------------------------------------------------------------------------------------

    private suspend fun awaitEvent(
        actor: ActorContext,
        spec: WaitForSpec,
    ): WaitResult {
        board.update(actor.agentId, AgentState.WAITING, actor.step.id, "wait_for ${spec.event}")
        tasks.update(actor.step.id, actor.agentId, TaskState.WAITING_EVENT, "wait_for ${spec.event}")
        val started = clock.now()
        val event = run.bus.await(spec.event, afterSequence = 0, timeout = spec.timeout) ?: return WaitResult.TimedOut(started)
        val stepId =
            evidence.step(
                run,
                actor.agentId,
                actor.step.id,
                StepKind.WAIT,
                "wait_for ${spec.event}",
                started,
                StepStatus.PASSED,
                "received ${event.name} (object ${event.objectId ?: "-"}, sequence ${event.sequence})",
                actor.correlationId,
                Tally.PASS,
            )
        return WaitResult.Received(Waited(event, clock.now(), stepId))
    }

    private suspend fun notReceived(
        actor: ActorContext,
        spec: WaitForSpec,
        startedAt: HarnessTimestamp,
    ): ActorStepResult {
        val detail = "$NOT_RECEIVED: ${spec.event} was not published within ${spec.timeout}"
        val waitStepId =
            evidence.step(
                run,
                actor.agentId,
                actor.step.id,
                StepKind.WAIT,
                "wait_for ${spec.event}",
                startedAt,
                StepStatus.FAILED,
                detail,
                actor.correlationId,
                Tally.FAIL,
            )
        evidence.screenshot(run, waitStepId, actor.agentId, actor.session)
        // An event that exists by now arrived after this receiver's deadline: record it as missed for that event.
        run.bus.latest(spec.event)?.let { recordReceipt(actor, it, received = false, t1 = null, latencyMs = null) }
        val reason = "not evaluated: ${spec.event} not received"
        skipAction(actor, reason)
        evidence.skippedAssertions(run, actor.stepId, actor.step.id, actor.agentId, actorSpecs(actor.step), reason)
        board.update(actor.agentId, AgentState.IDLE, actor.step.id, "$NOT_RECEIVED ${spec.event}")
        tasks.update(actor.step.id, actor.agentId, TaskState.FAILED, detail)
        return ActorStepResult(actor.identity, null, NOT_RECEIVED)
    }

    /** Reception checks and the receipt for the awaited event (see the class KDoc). */
    private suspend fun receive(
        actor: ActorContext,
        waited: Waited,
    ): Verification {
        val specs = receptionSpecs(actor.step)
        val verification =
            verifyActor(actor, waited.stepId, specs, templateContext(actor.identity, waited.event.objectId), waited.event.t0)
        val visible = verification.records.firstOrNull { it.type == VISIBLE_TEXT_TYPE }
        when {
            visible != null && visible.verdict == Verdict.PASSED -> {
                val t0 = waited.event.t0.wall
                val t1 = visible.latencyMs?.let(t0::plusMillis) ?: clock.now().wall
                recordReceipt(actor, waited.event, received = true, t1 = t1, latencyMs = visible.latencyMs)
            }

            specs.any { it is AssertionSpec.VisibleText } -> {
                recordReceipt(actor, waited.event, received = false, t1 = null, latencyMs = null)
            }

            else -> {
                recordReceipt(actor, waited.event, received = true, t1 = waited.at.wall, latencyMs = null)
            }
        }
        return verification
    }

    private suspend fun recordReceipt(
        actor: ActorContext,
        event: PublishedEvent,
        received: Boolean,
        t1: Instant?,
        latencyMs: Long?,
    ) {
        services.recorder.receipt(EventReceipt(event.eventId, run.runId, actor.agentId, received, t1, latencyMs))
        tasks.eventReceived(event.name, actor.agentId, latencyMs, received)
    }

    // --- action ---------------------------------------------------------------------------------------------------

    private fun render(
        action: StepAction,
        templates: TemplateContext,
    ): StepAction =
        when (action) {
            is StepAction.Do -> StepAction.Do(services.renderer.render(action.instruction, templates))
            is StepAction.Run -> action.copy(args = action.args.mapValues { services.renderer.render(it.value, templates) })
            StepAction.None -> StepAction.None
        }

    private suspend fun templateFailed(
        actor: ActorContext,
        error: TemplateException,
    ): ActorStepResult {
        val detail = "$TEMPLATE_ERROR: ${error.message}"
        evidence.step(
            run,
            actor.agentId,
            actor.step.id,
            kindOf(actor.step.action),
            describe(actor.step.action),
            clock.now(),
            StepStatus.FAILED,
            detail,
            actor.correlationId,
            Tally.FAIL,
            stepId = actor.stepId,
        )
        evidence.skippedAssertions(
            run,
            actor.stepId,
            actor.step.id,
            actor.agentId,
            afterActionSpecs(actor.step),
            "not evaluated: the step could not be rendered",
        )
        board.update(actor.agentId, AgentState.IDLE, actor.step.id, detail)
        tasks.update(actor.step.id, actor.agentId, TaskState.FAILED, detail)
        return ActorStepResult(actor.identity, null, TEMPLATE_ERROR)
    }

    private suspend fun skipAction(
        actor: ActorContext,
        reason: String,
    ) {
        evidence.step(
            run,
            actor.agentId,
            actor.step.id,
            kindOf(actor.step.action),
            describe(actor.step.action),
            clock.now(),
            StepStatus.SKIPPED,
            reason,
            actor.correlationId,
            Tally.NONE,
            stepId = actor.stepId,
        )
    }

    /** Runs the action; its record is written by [recordAction] (in a race step once every racer is done). */
    private suspend fun perform(
        actor: ActorContext,
        action: StepAction,
        templates: TemplateContext,
    ): Performed {
        val description = describe(action)
        board.update(actor.agentId, AgentState.WORKING, actor.step.id, description)
        tasks.update(actor.step.id, actor.agentId, TaskState.RUNNING, description)
        val started = clock.now()
        val outcome = if (action is StepAction.None) NOTHING_TO_DO else execute(actor, action, templates)
        return Performed(action, description, started, clock.now(), outcome)
    }

    /** The page as the action left it, when the agent could not leave evidence itself (it crashed or got stuck). */
    private suspend fun failureScreenshot(
        actor: ActorContext,
        outcome: ActionOutcome,
    ) {
        if (outcome.status == ActionStatus.ERROR || (outcome.status == ActionStatus.BLOCKED && !isRefusal(outcome))) {
            evidence.screenshot(run, actor.stepId, actor.agentId, actor.session)
        }
    }

    private suspend fun recordAction(
        actor: ActorContext,
        performed: Performed,
        record: ActionRecord,
    ) {
        evidence.step(
            run,
            actor.agentId,
            actor.step.id,
            kindOf(performed.action),
            performed.description,
            performed.startedAt,
            record.status,
            record.detail,
            actor.correlationId,
            record.tally,
            stepId = actor.stepId,
            endedAt = performed.endedAt,
        )
    }

    private suspend fun execute(
        actor: ActorContext,
        action: StepAction,
        templates: TemplateContext,
    ): ActionOutcome {
        val context =
            StepContext(
                scenarioStep = actor.step.id,
                correlationId = actor.correlationId,
                templates = templates,
                maxSteps = run.campaign.settings.budget.maxStepsPerAgent,
                timeout = remainingBudget(),
            )
        return try {
            services.watchdog.guard(actor.agentId, run.options.inactivityTimeout) { actor.agent.perform(action, context) }
        } catch (e: CancellationException) {
            // Our own cancellation (budget, abort) propagates; a stray one from inside the agent is an agent error.
            currentCoroutineContext().ensureActive()
            ActionOutcome(ActionStatus.ERROR, "action cancelled unexpectedly: ${e.message}")
        } catch (e: Exception) {
            ActionOutcome(
                status = ActionStatus.ERROR,
                summary = "${e::class.simpleName}: ${e.message}",
                failureReason = if (e is BrowserActionException) FailureReason.BROWSER_ERROR else null,
            )
        }
    }

    private fun remainingBudget(): Duration = (run.budget - run.startedAt.elapsedUntil(clock.now())).coerceAtLeast(Duration.ZERO)

    // --- races ----------------------------------------------------------------------------------------------------

    /**
     * Reads the actor's requests once so the session catches up on answers to requests sent before this step, then
     * takes the start time its race evidence is read from. A session that cannot be read now fails later, in [requestsOf].
     */
    private suspend fun startRace(actor: ActorContext): HarnessTimestamp {
        try {
            actor.session.mutations(clock.now())
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            logger.debug { "${actor.agentId}: requests could not be read before the race (${e.message})" }
        }
        return clock.now()
    }

    private suspend fun requestsOf(
        actor: ActorContext,
        spec: AssertionSpec.OnlyOneSucceeds,
        since: HarnessTimestamp,
    ): RaceEvidence =
        try {
            RaceEvidence.of(spec.effectiveRequest, actor.session.mutations(since))
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            RaceEvidence.unavailable("${e::class.simpleName}: ${e.message}")
        }

    /** Race actors are recorded and concluded once every actor of the step has acted; the others already are. */
    private suspend fun settle(runs: List<ActorRun>): List<ActorStepResult> {
        val winners = runs.filterIsInstance<ActorRun.Raced>().filter { it.acted.requests?.succeeded == true }.map { it.acted.agentId }
        return runs.map { run ->
            when (run) {
                is ActorRun.Settled -> {
                    run.result
                }

                is ActorRun.Raced -> {
                    val acted = run.acted
                    val lost = lostRace(acted, winners)
                    recordAction(
                        acted.actor,
                        acted.performed,
                        actionRecord(acted.actor.step, acted.performed.outcome, acted.requests, lost),
                    )
                    conclude(acted, lost)
                }
            }
        }
    }

    /**
     * The step stopped before every racer was done ([cause]): the ones that acted are recorded and concluded on their
     * own evidence, without a winner to lose to. A failure to record is attached to [cause] instead of hiding it.
     */
    private suspend fun settleUnjudged(
        racers: Collection<ActorRun.Raced>,
        cause: Exception,
    ) {
        racers.forEach { raced ->
            val acted = raced.acted
            try {
                recordAction(acted.actor, acted.performed, actionRecord(acted.actor.step, acted.performed.outcome, acted.requests, null))
                conclude(acted, lost = null)
            } catch (e: Exception) {
                cause.addSuppressed(e)
            }
        }
    }

    /**
     * Why [acted] lost the race, or null when it did not: the target refused it as already decided (409/422), or it
     * answered (success claimed, problem or refusal reported) without sending a matching request at all while another
     * actor won (it found the object decided). Any other answer to its own request (403, 400, 404, a 5xx) is not a
     * lost race but something the report must show: a permission refusal, or the target failing under the race. An
     * actor that crashed, timed out or was blocked did not get to answer, and one whose requests could not be read
     * may have won as well.
     */
    private fun lostRace(
        acted: Acted,
        winners: List<AgentId>,
    ): LostRace? {
        val requests = acted.requests ?: return null
        if (requests.succeeded || requests.unavailable != null) return null
        val others = winners.filter { it != acted.agentId }
        val lost =
            requests.refusedAsDecided ||
                (requests.decisive == null && others.isNotEmpty() && answered(acted.performed.outcome))
        return if (lost) LostRace(requests, others) else null
    }

    /**
     * The agent claimed success, but the target turned down the actor's own request in this race (status >= 400 and
     * not a lost race): code decides (CLAUDE.md rule 2), so the action failed with [REQUEST_FAILED].
     */
    private fun refutedClaim(
        outcome: ActionOutcome,
        requests: RaceEvidence?,
        lost: LostRace?,
    ): Boolean =
        lost == null &&
            outcome.succeeded &&
            requests != null &&
            requests.unavailable == null &&
            !requests.succeeded &&
            requests.decisive != null

    private fun answered(outcome: ActionOutcome): Boolean =
        outcome.status == ActionStatus.SUCCEEDED ||
            outcome.failureReason == FailureReason.PROBLEM_REPORTED ||
            outcome.failureReason == FailureReason.PERMISSION_DENIED

    // --- emits ----------------------------------------------------------------------------------------------------

    private suspend fun emit(
        actor: ActorContext,
        spec: EmitSpec,
        outcome: ActionOutcome,
        templates: TemplateContext,
    ): Emitted {
        val started = clock.now()
        val source = spec.idSource ?: run.campaign.target.idSource(spec.event)
        val resolution = services.objectIds.read(source, actor.session, outcome, templates)
        val event = run.bus.publish(spec.event, resolution.objectId, actor.agentId)
        tasks.eventPublished(event)
        services.recorder.event(
            EventRecord(
                eventId = event.eventId,
                runId = run.runId,
                name = event.name,
                emitter = event.emitter,
                objectId = event.objectId,
                objectIdSource = resolution.source,
                payloadJson = payload(actor, event, resolution),
                t0 = event.t0.wall,
            ),
        )
        val failed = resolution.problem != null
        evidence.step(
            run,
            actor.agentId,
            actor.step.id,
            StepKind.EMIT,
            "emit ${spec.event}",
            started,
            if (failed) StepStatus.FAILED else StepStatus.PASSED,
            emitDetail(event, resolution),
            actor.correlationId,
            if (failed) Tally.FAIL else Tally.PASS,
        )
        return Emitted(event, resolution.problem)
    }

    private fun payload(
        actor: ActorContext,
        event: PublishedEvent,
        resolution: ObjectIdResolution,
    ): String =
        buildJsonObject {
            put("id", event.objectId)
            put("actor", event.emitter.value)
            put("t0", event.t0.wall.toString())
            put("sequence", event.sequence)
            put("scenario_step", actor.step.id)
            put("id_source", resolution.source)
        }.toString()

    private fun emitDetail(
        event: PublishedEvent,
        resolution: ObjectIdResolution,
    ): String =
        buildString {
            append("${event.name} id=${event.objectId ?: "-"}")
            resolution.source?.let { append(" ($it)") }
            resolution.problem?.let { append("; $ID_UNAVAILABLE: $it") }
            resolution.note?.let { append("; $it") }
        }

    // --- assertions -----------------------------------------------------------------------------------------------

    private suspend fun verifyActor(
        actor: ActorContext,
        stepId: StepId,
        specs: List<AssertionSpec>,
        templates: TemplateContext,
        t0: HarnessTimestamp?,
    ): Verification {
        if (specs.isEmpty()) return Verification(emptyList(), error = false)
        val input = AssertionInput(run.runId, stepId, actor.step.id, actor.agentId, actor.session, templates, t0)
        return try {
            val records = services.verify.verifyActor(specs, input)
            records.filter { it.verdict == Verdict.FAILED }.forEach { run.tally.assertionFailed(actor.agentId) }
            Verification(records, error = false)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            recordVerificationError(actor.step.id, actor.agentId, actor.correlationId, e)
            Verification(emptyList(), error = true)
        }
    }

    private suspend fun verifyGroup(
        step: ScenarioStep,
        results: List<ActorStepResult>,
    ): Boolean {
        val specs = groupSpecs(step)
        if (specs.isEmpty()) return false
        val started = clock.now()
        val stepId = ids.stepId()
        val correlationId = ids.correlationId()
        // The group verdict comes after every actor acted: `{last_id}` is the winner's object (the only emitted one).
        val input = AssertionInput(run.runId, stepId, step.id, null, null, templateContext(null, run.bus.latestAny()?.objectId), null)
        val actorResults =
            results.map {
                ActorResult(
                    agentId = it.identity.agentId,
                    succeeded = it.race?.succeeded == true,
                    summary = it.outcome?.summary ?: it.failureKey.orEmpty(),
                    race = it.race,
                    lostRace = it.lostRace,
                )
            }
        val records =
            try {
                services.verify.verifyGroup(specs, input, actorResults)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                recordVerificationError(step.id, null, correlationId, e)
                return true
            }
        val failures = records.count { it.verdict == Verdict.FAILED }
        repeat(failures) { run.tally.assertionFailed(null) }
        val failed = failures > 0
        evidence.step(
            run,
            null,
            step.id,
            StepKind.SYSTEM,
            "verify_group ${specs.joinToString(",") { it.type }}",
            started,
            if (failed) StepStatus.FAILED else StepStatus.PASSED,
            records.joinToString("; ") { "${it.type}: ${it.verdict} (${it.observed ?: "-"})" },
            correlationId,
            Tally.NONE,
            stepId = stepId,
        )
        return failed
    }

    private suspend fun recordVerificationError(
        scenarioStep: String,
        agentId: AgentId?,
        correlationId: CorrelationId,
        error: Exception,
    ) {
        evidence.step(
            run,
            agentId,
            scenarioStep,
            StepKind.ASSERT,
            "verify",
            clock.now(),
            StepStatus.ERROR,
            "verification failed: ${error::class.simpleName}: ${error.message}",
            correlationId,
            Tally.FAIL,
        )
    }

    // --- conclusion -----------------------------------------------------------------------------------------------

    private fun conclude(
        acted: Acted,
        lost: LostRace?,
    ): ActorStepResult {
        val actor = acted.actor
        val outcome = acted.performed.outcome
        val refuted = refutedClaim(outcome, acted.requests, lost)
        val failureKey =
            when {
                lost == null && !outcome.succeeded && !isExpectedRefusal(actor.step, outcome) -> {
                    outcome.failureReason?.key ?: outcome.status.name.lowercase()
                }

                refuted -> {
                    REQUEST_FAILED
                }

                acted.emitted?.problem != null -> {
                    ID_UNAVAILABLE
                }

                acted.checks.any { it.error } -> {
                    VERIFICATION_ERROR
                }

                acted.checks.any { it.failed } -> {
                    ASSERTION_FAILED
                }

                else -> {
                    null
                }
            }
        val blocked = outcome.status == ActionStatus.BLOCKED && !isRefusal(outcome) && lost == null
        val state = if (blocked) AgentState.BLOCKED else AgentState.IDLE
        val result =
            when {
                failureKey != null -> failureKey
                lost != null -> LOST_RACE
                isExpectedRefusal(actor.step, outcome) -> FailureReason.PERMISSION_DENIED.key
                else -> "ok"
            }
        board.update(actor.agentId, state, actor.step.id, "$result: ${outcome.summary}")
        val task =
            when {
                failureKey != null && blocked -> TaskState.BLOCKED
                failureKey != null -> TaskState.FAILED
                lost != null -> TaskState.LOST_RACE
                else -> TaskState.PASSED
            }
        val detail =
            when {
                lost != null && failureKey == null -> lostDetail(lost, outcome)
                refuted -> refutedDetail(requireNotNull(acted.requests), outcome)
                else -> "$result: ${outcome.summary}"
            }
        tasks.update(actor.step.id, actor.agentId, task, detail)
        return ActorStepResult(actor.identity, outcome, failureKey, acted.requests, lostRace = lost != null)
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    /** [lastId] is the actor's own emitted object, the one it waited for, or the newest before the step (see [execute]). */
    private fun templateContext(
        identity: Identity?,
        lastId: String?,
    ): TemplateContext =
        TemplateContext(
            lastId = lastId,
            self = identity?.let(::selfFields).orEmpty(),
            eventIds = latestEventIds(),
        )

    /** `{event.<name>.id}`: the object id of the newest event of every name the campaign emits. */
    private fun latestEventIds(): Map<String, String> =
        buildMap {
            run.eventNames.forEach { name ->
                run.bus
                    .latest(name)
                    ?.objectId
                    ?.let { put(name, it) }
            }
        }

    /**
     * How an action is recorded: a lost race is PASSED with `lost_race: ...` (expected); a success claim the actor's
     * own request refutes is FAILED with `request_failed: ...`; otherwise the agent's outcome decides, and in a race
     * step the detail adds what the actor's requests showed.
     */
    private fun actionRecord(
        step: ScenarioStep,
        outcome: ActionOutcome,
        race: RaceEvidence?,
        lost: LostRace?,
    ): ActionRecord {
        if (lost != null) return ActionRecord(StepStatus.PASSED, lostDetail(lost, outcome), Tally.PASS)
        if (race != null && refutedClaim(outcome, race, lost = null)) {
            return ActionRecord(StepStatus.FAILED, refutedDetail(race, outcome), Tally.FAIL)
        }
        val detail =
            listOfNotNull(detailOf(outcome), race?.let { "request: ${it.describe()}" })
                .joinToString("; ")
                .ifBlank { null }
        return ActionRecord(statusOf(outcome), detail, tallyOf(step, outcome))
    }

    /** `lost_race: POST /tickets/t2/approve -> 409; won by a02; agent: <summary>`. */
    private fun lostDetail(
        lost: LostRace,
        outcome: ActionOutcome,
    ): String =
        buildString {
            append(LOST_RACE).append(": ").append(lost.requests.describe())
            if (lost.winners.isNotEmpty()) append("; won by ").append(lost.winners.joinToString(", ") { it.value })
            if (outcome.summary.isNotBlank()) append("; agent: ").append(outcome.summary)
        }

    /** `request_failed: POST /tickets/t2/approve -> 500; agent: <summary>`. */
    private fun refutedDetail(
        requests: RaceEvidence,
        outcome: ActionOutcome,
    ): String =
        buildString {
            append(REQUEST_FAILED).append(": ").append(requests.describe())
            if (outcome.summary.isNotBlank()) append("; agent: ").append(outcome.summary)
        }

    private fun tallyOf(
        step: ScenarioStep,
        outcome: ActionOutcome,
    ): Tally =
        when {
            outcome.succeeded -> Tally.PASS
            isExpectedRefusal(step, outcome) -> Tally.NONE
            else -> Tally.FAIL
        }

    /**
     * A refusal the agent reports (`permission_denied`) in a main step is what permission tests expect; their
     * assertions decide. In setup it is a real failure: the tester cannot get in.
     */
    private fun isExpectedRefusal(
        step: ScenarioStep,
        outcome: ActionOutcome,
    ): Boolean = step.phase == StepPhase.MAIN && isRefusal(outcome)

    private fun isRefusal(outcome: ActionOutcome): Boolean =
        outcome.status == ActionStatus.BLOCKED && outcome.failureReason == FailureReason.PERMISSION_DENIED

    private inner class ActorContext(
        val step: ScenarioStep,
        val identity: Identity,
        val session: BrowserSession,
        val agent: TesterAgent,
    ) {
        val agentId = identity.agentId
        val stepId: StepId = ids.stepId()
        val correlationId: CorrelationId = ids.correlationId()
    }

    /** One actor's run of a step: final ([Settled]), or waiting for the other racers to be judged ([Raced]). */
    private sealed interface ActorRun {
        class Settled(
            val result: ActorStepResult,
        ) : ActorRun

        class Raced(
            val acted: Acted,
        ) : ActorRun
    }

    /** Everything an actor did in a step once its action ran; [requests] is set in a race step. */
    private class Acted(
        val actor: ActorContext,
        val performed: Performed,
        val requests: RaceEvidence?,
        val emitted: Emitted?,
        val checks: List<Verification>,
    ) {
        val agentId: AgentId get() = actor.agentId
    }

    private class Performed(
        val action: StepAction,
        val description: String,
        val startedAt: HarnessTimestamp,
        val endedAt: HarnessTimestamp,
        val outcome: ActionOutcome,
    )

    private class ActionRecord(
        val status: StepStatus,
        val detail: String?,
        val tally: Tally,
    )

    private class LostRace(
        val requests: RaceEvidence,
        val winners: List<AgentId>,
    )

    private sealed interface WaitResult {
        data class Received(
            val waited: Waited,
        ) : WaitResult

        data class TimedOut(
            val startedAt: HarnessTimestamp,
        ) : WaitResult
    }

    private data class Waited(
        val event: PublishedEvent,
        val at: HarnessTimestamp,
        val stepId: StepId,
    )

    private data class Emitted(
        val event: PublishedEvent,
        val problem: String?,
    )

    private data class Verification(
        val records: List<AssertionRecord>,
        val error: Boolean,
    ) {
        val failed: Boolean get() = error || records.any { it.verdict == Verdict.FAILED }
    }

    companion object {
        const val NOT_RECEIVED = "not_received"
        const val TEMPLATE_ERROR = "template_error"
        const val ID_UNAVAILABLE = "id_unavailable"
        const val ASSERTION_FAILED = "assertion_failed"
        const val VERIFICATION_ERROR = "verification_error"

        /** Detail key of an action that lost a race: an expected outcome, not a failure (see reporting's FailureKeys). */
        const val LOST_RACE = "lost_race"

        /**
         * Failure key of a race action whose agent claimed success while the target turned down the actor's own
         * request (e.g. a 500 or 403 on the approval): the request, not the agent, decides (CLAUDE.md rule 2).
         */
        const val REQUEST_FAILED = "request_failed"

        private const val VISIBLE_TEXT_TYPE = "visible_text"
        private val NOTHING_TO_DO = ActionOutcome(ActionStatus.SUCCEEDED, "nothing to do")

        fun selfFields(identity: Identity): Map<String, String> =
            buildMap {
                put("email", identity.email)
                put("name", identity.displayName)
                put("agent_id", identity.agentId.value)
                put("role", identity.role.key)
                put("phone", identity.phone)
                identity.department?.let { put("department", it) }
            }

        fun describe(action: StepAction): String =
            when (action) {
                is StepAction.Do -> {
                    "do: ${action.instruction}"
                }

                is StepAction.Run -> {
                    val args = action.args.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    if (args.isEmpty()) "run ${action.function}" else "run ${action.function} ($args)"
                }

                StepAction.None -> {
                    "none"
                }
            }

        fun kindOf(action: StepAction): StepKind =
            when (action) {
                is StepAction.Do -> StepKind.DO
                is StepAction.Run -> StepKind.RUN
                StepAction.None -> StepKind.SYSTEM
            }

        fun statusOf(outcome: ActionOutcome): StepStatus =
            when (outcome.status) {
                ActionStatus.SUCCEEDED -> StepStatus.PASSED
                ActionStatus.FAILED -> StepStatus.FAILED
                ActionStatus.BLOCKED -> StepStatus.BLOCKED
                ActionStatus.ERROR -> StepStatus.ERROR
            }

        /** `<failure key>: <summary>` so the report can classify the failure (see reporting's judge). */
        fun detailOf(outcome: ActionOutcome): String? =
            listOfNotNull(outcome.failureReason?.key, outcome.summary.takeIf { it.isNotBlank() })
                .joinToString(": ")
                .ifBlank { null }

        fun actorSpecs(step: ScenarioStep): List<AssertionSpec> = step.assertions.filterNot { it is AssertionSpec.OnlyOneSucceeds }

        fun groupSpecs(step: ScenarioStep): List<AssertionSpec> = step.assertions.filter { it is AssertionSpec.OnlyOneSucceeds }

        /** The step's race, if it asserts one (the validator allows one per step); its actors' requests decide it. */
        fun raceSpec(step: ScenarioStep): AssertionSpec.OnlyOneSucceeds? =
            step.assertions.firstNotNullOfOrNull { it as? AssertionSpec.OnlyOneSucceeds }

        /** Checks evaluated right after the awaited event arrives (empty without `wait_for`). */
        fun receptionSpecs(step: ScenarioStep): List<AssertionSpec> =
            if (step.waitFor == null) emptyList() else actorSpecs(step).filter { it.isReceptionCheck() }

        fun afterActionSpecs(step: ScenarioStep): List<AssertionSpec> =
            if (step.waitFor == null) actorSpecs(step) else actorSpecs(step).filterNot { it.isReceptionCheck() }

        private fun AssertionSpec.isReceptionCheck(): Boolean = this is AssertionSpec.VisibleText || this is AssertionSpec.LatencyMax
    }
}

/** Collaborators of [StepExecutor] that do not change during a run. */
internal class StepServices(
    val resolver: ActorResolver,
    val renderer: TemplateRenderer,
    val verify: VerifyStepUseCase,
    val watchdog: InactivityWatchdog,
    val objectIds: ObjectIdReader,
    val recorder: EvidenceRecorder,
    val evidence: HarnessEvidence,
    val board: AgentBoard,
    val tasks: TaskBoard,
    val clock: HarnessClock,
    val ids: IdGenerator,
    val diagnostics: DiagnosticContext,
)
