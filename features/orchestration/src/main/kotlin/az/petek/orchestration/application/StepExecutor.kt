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

import az.petek.agent.application.TesterAgent
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.ActorShare
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.TextWatch
import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.Pacing
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TemplateContext
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer
import az.petek.campaign.domain.WaitForSpec
import az.petek.campaign.domain.apiOriginInUse
import az.petek.campaign.domain.expectsRefusal
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
import az.petek.evidence.domain.SKIP_ACTION
import az.petek.evidence.domain.SkipDetail
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.ActorResolver
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.EventOrigin
import az.petek.orchestration.domain.EventWrite
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.TaskState
import az.petek.verification.application.VerifyStepUseCase
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionInput
import az.petek.verification.domain.EventTime
import az.petek.verification.domain.RaceEvidence
import az.petek.verification.domain.WatchedText
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
 * (design decision 3: t1 - t0 is the real delivery latency). Every other assertion runs after the action, unless the
 * action did not complete (an error, or a stop that is no refusal): then they are recorded as not evaluated, since the
 * page and the site's records after a tester's stop say nothing about the site ([unfinished]).
 *
 * Races (`only_one_succeeds`): whether an actor succeeded is decided by code from the requests its own browser sent
 * during the action ([BrowserSession.mutations], judged by [RaceEvidence]), never by the agent's `done(success)` or
 * summary (AGENTS.md rule 2). The session is read once right before the action's start time is taken (so answers to
 * earlier requests are timestamped before it) and once after the action. Only an actor whose requests succeeded
 * emits the step's event. An actor that lost the race — the target refused it with 409/422, or it gave its answer
 * (success, a reported problem or a refusal) without sending a matching request while another actor won — did what a
 * race expects: its action is recorded PASSED with detail `lost_race: ...`, it does not count as a failed agent, and
 * the group assertion decides. Any other refusal or error answer to its own request is no lost race; when its agent
 * claimed success anyway, the action is FAILED with `request_failed: ...`. Because all that needs every actor's
 * evidence, the action records of a race step are written once all its actors are done (with their own start and end
 * times); when the step is interrupted first (budget, abort), the racers that already acted are recorded unjudged.
 *
 * Forbidden actions: in a main step that expects a refusal, the mutating requests (POST, PUT, PATCH, DELETE) the
 * actor's own page sent during the action are read like a racer's; one the site accepted on the method and path of an
 * `http_status` assertion that expects 401/403 fails the action with `forbidden_accepted`, a defect of the site
 * ([refusalBreached]). A page the role must not see (a GET) is not among them: the `http_status` probe itself, sent
 * with the actor's session, catches a site that shows it.
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
        run.stepStarted(step.id)
        recordSkippedFailedActors(step)
        val chosen = resolver.resolve(step.actors, run.activeIdentities())
        run.executedActors.merge(step.id, chosen.map { it.agentId }) { before, now -> before + now }
        val out = resolver.resolve(step.actors.anyPlace(), run.waveIdentities()).map { it.agentId }.filter(run::isFailed)
        run.stepChose(step.id, chosen.map { it.agentId }, out)
        releaseWatches(step.id, keep = chosen.map { it.agentId }.toSet())
        if (chosen.isEmpty()) {
            run.stepHadNoTester(step.id)
            evidence.system(run, null, SKIP_ACTION, StepStatus.SKIPPED, SkipDetail.noActor(step.actors.raw), step.id)
            return StepResult(step, emptyList(), groupFailed = false)
        }
        armReceivers(step, chosen)
        val race = raceSpec(step)
        val barrier = if (step.parallel) StartBarrier(chosen.size) else null
        // A step without an action only checks the page: nothing reaches the target that pacing would spread out.
        val pacing = if (step.parallel || step.action is StepAction.None) Pacing.NONE else run.campaign.settings.pacing
        val raced = ConcurrentLinkedQueue<ActorRun.Raced>()
        // Each actor's place among the step's actors, for run functions that share the step's work out.
        val shares =
            chosen
                .map { it.agentId }
                .sorted()
                .withIndex()
                .associate { (position, agentId) -> agentId to ActorShare(position, chosen.size) }
        val runs =
            try {
                coroutineScope {
                    val pacer = StartPacer(this, pacing, chosen.map { it.agentId })
                    try {
                        chosen
                            .map { identity ->
                                async(services.diagnostics.of(run.runId, identity.agentId)) {
                                    runActor(
                                        step,
                                        identity,
                                        shares.getValue(identity.agentId),
                                        barrier,
                                        race,
                                        pacer,
                                    ).also {
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
        // Every racer was skipped because the event's step had no tester here: there was no race to judge.
        if (results.all { it.outcome == null && !it.failed } && groupSpecs(step).isNotEmpty()) {
            val reason = "not evaluated: none of the actors could wait for their event here"
            evidence.skippedAssertions(run, ids.stepId(), step.id, null, groupSpecs(step), reason)
            return StepResult(step, results, groupFailed = false)
        }
        return StepResult(step, results, groupFailed = verifyGroup(step, results))
    }

    private suspend fun recordSkippedFailedActors(step: ScenarioStep) {
        resolver
            .resolve(step.actors, run.waveIdentities())
            .filter { run.isFailed(it.agentId) }
            .forEach {
                val reason = run.failureReason(it.agentId) ?: "failed"
                val detail = SkipDetail.failedEarlier(reason)
                evidence.system(run, it.agentId, SKIP_ACTION, StepStatus.SKIPPED, detail, step.id)
                run.settle(step.id, it.agentId)
                tasks.update(step.id, it.agentId, TaskState.SKIPPED, detail)
            }
    }

    private suspend fun runActor(
        step: ScenarioStep,
        identity: Identity,
        share: ActorShare,
        barrier: StartBarrier?,
        race: AssertionSpec.OnlyOneSucceeds?,
        pacer: StartPacer,
    ): ActorRun {
        var arrived = false
        try {
            val actor =
                ActorContext(step, identity, run.sessions.getValue(identity.agentId), run.agents.getValue(identity.agentId), share)
            // The step's requests carry its correlation id when the owner turned the header on (Faza 14).
            actor.session.setCorrelationId(actor.correlationId.value)
            val waited =
                step.waitFor?.let { spec ->
                    run.absentEmitter(spec.event)?.let { emitter ->
                        releaseWatch(actor)
                        return ActorRun.Settled(withoutEmitter(actor, spec, emitter))
                    }
                    run.receiversOf(step.id).waited.incrementAndGet()
                    when (val result = awaitEvent(actor, spec)) {
                        is WaitResult.Received -> {
                            result.waited
                        }

                        is WaitResult.TimedOut -> {
                            releaseWatch(actor)
                            return ActorRun.Settled(notReceived(actor, spec, result.startedAt))
                        }
                    }
                }
            val reception = waited?.let { receive(actor, it) }
            // `{last_id}` is the step's own event only (Faza 24.6): before the action, the one it waited for.
            val templates = templateContext(identity, waited?.event?.objectId, passOf(step, waited?.event))
            val action =
                try {
                    render(step.action, templates)
                } catch (e: TemplateException) {
                    return ActorRun.Settled(templateFailed(actor, e))
                }
            val forbidden = forbiddenRequests(step, templates)
            // A race, a forbidden action and an emitted event are all judged or timed from the actor's own requests.
            val watchStart = if (race != null || forbidden.isNotEmpty() || step.emits != null) startWatching(actor) else null
            if (barrier != null) {
                barrier.arrive()
                arrived = true
                barrier.awaitOpen()
            }
            val performed =
                pacer.paced(identity.agentId, { board.update(identity.agentId, AgentState.WAITING, step.id, it) }) {
                    perform(actor, action, templates, forbidden, watchStart)
                }
            val requests = if (race != null && watchStart != null) requestsOf(actor, race, watchStart) else null
            if (requests == null) recordAction(actor, performed, actionRecord(step, performed.outcome, race = null, lost = null))
            failureScreenshot(actor, performed.outcome)
            val succeeded = requests?.succeeded ?: performed.outcome.succeeded
            val emitted = if (succeeded) step.emits?.let { emit(actor, it, performed, templates, watchStart) } else null
            // In the checks: the object this actor emitted when the step emits, else the one it waited for; never another's.
            val lastId = if (step.emits != null) emitted?.event?.objectId else waited?.event?.objectId
            val checks =
                unfinished(performed.outcome)?.let { key ->
                    // What the site shows after an action that never completed says nothing about the site (see [unfinished]).
                    val reason = "not evaluated: the action did not complete ($key)"
                    evidence.skippedAssertions(run, actor.stepId, step.id, identity.agentId, afterActionSpecs(step), reason)
                    Verification(emptyList(), error = false)
                } ?: verifyActor(
                    actor,
                    actor.stepId,
                    afterActionSpecs(step),
                    templateContext(identity, lastId, passOf(step, waited?.event)),
                    waited?.event?.let(::eventTime),
                )
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
        // Only the latest execution of the emitting step counts: never the first pass's event in the account swap.
        val event =
            run.bus.await(spec.event, afterSequence = run.eventCursor(spec.event), timeout = spec.timeout)
                ?: return WaitResult.TimedOut(started)
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
        run.bus
            .latest(spec.event)
            ?.takeIf { it.sequence > run.eventCursor(spec.event) }
            ?.let { recordReceipt(actor, it, received = false, t1 = null, latencyMs = null) }
        val reason = "not evaluated: ${spec.event} not received"
        skipAction(actor, reason)
        evidence.skippedAssertions(run, actor.stepId, actor.step.id, actor.agentId, actorSpecs(actor.step), reason)
        board.update(actor.agentId, AgentState.IDLE, actor.step.id, "$NOT_RECEIVED ${spec.event}")
        tasks.update(actor.step.id, actor.agentId, TaskState.FAILED, detail)
        return ActorStepResult(actor.identity, null, NOT_RECEIVED)
    }

    /**
     * The step that emits [spec]'s event had no tester here (Faza 24.7): a wave without its testers, or a run whose
     * testers of that step are all out. Nothing can arrive, so the receiver is skipped at once, saying why, instead of
     * failing after a pointless wait. The run's coverage counts it ([DefaultCampaignRunner]): a step no receiver could
     * check anywhere is `not_covered`. In setup the tester cannot finish getting ready without the event, so it is left
     * out of the later steps with [EMITTER_ABSENT], never counted as set up.
     */
    private suspend fun withoutEmitter(
        actor: ActorContext,
        spec: WaitForSpec,
        emitter: String,
    ): ActorStepResult {
        run.receiversOf(actor.step.id).withoutEmitter.incrementAndGet()
        val where = if (run.wave != null) "its testers are in another wave" else "none of its testers is in the run"
        val detail = "$EMITTER_ABSENT: step '$emitter', which emits ${spec.event}, had no tester here ($where)"
        evidence.step(
            run,
            actor.agentId,
            actor.step.id,
            StepKind.WAIT,
            "wait_for ${spec.event}",
            clock.now(),
            StepStatus.SKIPPED,
            detail,
            actor.correlationId,
            Tally.NONE,
        )
        val reason = "not evaluated: $detail"
        skipAction(actor, reason)
        evidence.skippedAssertions(run, actor.stepId, actor.step.id, actor.agentId, actorSpecs(actor.step), reason)
        board.update(actor.agentId, AgentState.IDLE, actor.step.id, EMITTER_ABSENT)
        val setup = actor.step.phase == StepPhase.SETUP
        tasks.update(actor.step.id, actor.agentId, if (setup) TaskState.FAILED else TaskState.SKIPPED, detail)
        return ActorStepResult(actor.identity, null, failureKey = if (setup) EMITTER_ABSENT else null)
    }

    /**
     * Reception checks and the receipt for the awaited event (see the class KDoc). Latency is measured from the write
     * behind the event ([eventTime]); the text check reads the watch this receiver started before that write, when it
     * has one ([armReceivers]).
     */
    private suspend fun receive(
        actor: ActorContext,
        waited: Waited,
    ): Verification {
        val specs = receptionSpecs(actor.step)
        val time = eventTime(waited.event)
        val watch = run.takeWatch(actor.step.id, actor.agentId)?.let { WatchedText(it.text, read(actor, it)) }
        val templates = templateContext(actor.identity, waited.event.objectId, passOf(actor.step, waited.event))
        // An event of an earlier pass (a setup event carried into a later wave, or read in the swap) was delivered then:
        // its text is checked as shown, never timed from a write long past.
        val earlier =
            waited.event
                .takeIf { it.pass < run.pass }
                ?.let { "'${it.name}' was published in pass ${it.pass} and is read in pass ${run.pass}" }
        val verification = verifyActor(actor, waited.stepId, specs, templates, time, watch, earlier)
        val visible = verification.records.firstOrNull { it.type == VISIBLE_TEXT_TYPE }
        when {
            visible != null && visible.verdict == Verdict.PASSED -> {
                val t0 = time.t0.wall
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
        run.settle(actor.step.id, actor.agentId)
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
        run.settle(actor.step.id, actor.agentId)
    }

    /**
     * Runs the action; its record is written by [recordAction] (in a race step once every racer is done). In a
     * forbidden-action step the requests the page sent since [watchStart] are checked against [forbidden] before the
     * agent's own words are judged ([refusalBreached], then [judgeRefusal]).
     */
    private suspend fun perform(
        actor: ActorContext,
        action: StepAction,
        templates: TemplateContext,
        forbidden: List<ForbiddenRequest> = emptyList(),
        watchStart: HarnessTimestamp? = null,
    ): Performed {
        val description = describe(action)
        board.update(actor.agentId, AgentState.WORKING, actor.step.id, description)
        tasks.update(actor.step.id, actor.agentId, TaskState.RUNNING, description)
        val started = clock.now()
        val outcome = if (action is StepAction.None) NOTHING_TO_DO else execute(actor, action, templates)
        val checked = refusalBreached(actor, rateLimited(actor, outcome, started), forbidden, watchStart)
        return Performed(action, description, started, clock.now(), judgeRefusal(actor.step, checked))
    }

    /**
     * A failed action during which the site answered `429 Too Many Requests` hit the site's rate limit (Faza 21):
     * reported as `rate_limited`, a gap of the set-up, not as whatever the agent concluded. Its summary says whether the
     * testers shared this machine's IP or each had its own proxy.
     */
    private suspend fun rateLimited(
        actor: ActorContext,
        outcome: ActionOutcome,
        since: HarnessTimestamp,
    ): ActionOutcome {
        if (outcome.succeeded) return outcome
        val limited =
            try {
                actor.session.mutations(since).any { it.status == TOO_MANY_REQUESTS } ||
                    actor.session
                        .health(since, Duration.INFINITE)
                        .failedRequests
                        .any { it.endsWith("-> $TOO_MANY_REQUESTS") }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        if (!limited) return outcome
        val addresses = if (run.proxies.isEmpty()) "every tester comes from one IP" else "although every tester has its own IP"
        return outcome.copy(
            failureReason = FailureReason.RATE_LIMITED,
            summary = "The site answered 429 Too Many Requests: $addresses. ${outcome.summary}",
        )
    }

    /**
     * A forbidden-action test ([ScenarioStep.expectsRefusal], main steps only) is decided by its assertions, never by
     * how the agent worded its stop (AGENTS.md rule 2): an agent that reported a problem or gave up (`report_problem`
     * of any kind, `done` with success=false) in such a step is recorded like a `permission_denied` refusal, its own
     * words kept. Errors and guard stops (timeout, step limit, loop, invalid decisions, browser or LLM failures) stay
     * what they are: they say nothing about whether the target refused.
     */
    private fun judgeRefusal(
        step: ScenarioStep,
        outcome: ActionOutcome,
    ): ActionOutcome {
        if (step.phase != StepPhase.MAIN || !step.expectsRefusal || isRefusal(outcome) || !stoppedByAgent(outcome)) return outcome
        val reported = outcome.failureReason?.key ?: "failure"
        return outcome.copy(
            status = ActionStatus.BLOCKED,
            failureReason = FailureReason.PERMISSION_DENIED,
            summary = "${outcome.summary} (agent reported $reported; the step expects a refusal, its assertions decide)",
        )
    }

    /** The agent itself ended the action without success: a problem it reported or a task it gave up on. */
    private fun stoppedByAgent(outcome: ActionOutcome): Boolean =
        (outcome.status == ActionStatus.FAILED || outcome.status == ActionStatus.BLOCKED) &&
            (outcome.failureReason == FailureReason.PROBLEM_REPORTED || outcome.failureReason == FailureReason.PERMISSION_DENIED)

    /**
     * The failure key of an action that did not complete, or null when it did (or ended on the agent's own answer): an
     * error, or a stop that is no refusal (the watchdog's `timeout`, `llm_unavailable`). The checks after such an action
     * are recorded as not evaluated instead of run: a tester stopped before it opened the list leaves no read receipt,
     * and that missing receipt is the tester's stop, never a defect of the site. The action's own record carries the
     * failure. A refusal and a problem the agent reported are answers about the site, so their checks still run.
     */
    private fun unfinished(outcome: ActionOutcome): String? {
        val stopped = outcome.status == ActionStatus.ERROR || (outcome.status == ActionStatus.BLOCKED && !isRefusal(outcome))
        return if (stopped) outcome.failureReason?.key ?: outcome.status.name.lowercase() else null
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
        run.settle(actor.step.id, actor.agentId)
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
                share = actor.share,
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

    // --- forbidden actions ----------------------------------------------------------------------------------------

    /**
     * The requests a forbidden-action step (main steps only) expects the site to refuse: the mutating method and path of
     * each `http_status` assertion that expects 401 or 403, rendered for this actor. One whose path cannot be rendered is
     * left to its assertion, which reports the template error.
     */
    private fun forbiddenRequests(
        step: ScenarioStep,
        templates: TemplateContext,
    ): List<ForbiddenRequest> {
        if (step.phase != StepPhase.MAIN || !step.expectsRefusal) return emptyList()
        return step.assertions
            .filterIsInstance<AssertionSpec.HttpStatus>()
            .filter { it.expectsRefusal && it.method.uppercase() in RequestPattern.MUTATING_METHODS }
            .mapNotNull { spec ->
                try {
                    ForbiddenRequest(spec.method.uppercase(), services.renderer.render(spec.path, templates).substringBefore('?'))
                } catch (_: TemplateException) {
                    null
                }
            }
    }

    /**
     * In a forbidden-action step the refusal is checked by code from the actor's own requests too, not only by the
     * assertions that probe afterwards: when its page sent one of the [forbidden] requests during the action and the
     * site accepted it (status < 400), the site let a tester do what it must refuse. The action is then FAILED with
     * `forbidden_accepted` (a defect of the site, whatever the agent said), with a screenshot of the page.
     */
    private suspend fun refusalBreached(
        actor: ActorContext,
        outcome: ActionOutcome,
        forbidden: List<ForbiddenRequest>,
        since: HarnessTimestamp?,
    ): ActionOutcome {
        if (forbidden.isEmpty() || since == null) return outcome
        val accepted =
            try {
                actor.session.mutations(since).firstOrNull { sent ->
                    sent.status < RaceEvidence.ACCEPTED_BELOW && forbidden.any { it.matches(sent.method, sent.path) }
                }
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                logger.debug { "${actor.agentId}: requests could not be read after a forbidden action (${e.message})" }
                null
            } ?: return outcome
        evidence.screenshot(run, actor.stepId, actor.agentId, actor.session)
        val agent =
            outcome.summary
                .takeIf { it.isNotBlank() }
                ?.let { "; agent: $it" }
                .orEmpty()
        return ActionOutcome(
            status = ActionStatus.FAILED,
            summary = "${accepted.describe()} was accepted, although this step expects the site to refuse it$agent",
            failureReason = FailureReason.FORBIDDEN_ACCEPTED,
        )
    }

    // --- races ----------------------------------------------------------------------------------------------------

    /**
     * Reads the actor's requests once so the session catches up on answers to requests sent before this step, then
     * takes the start time the requests of its action are read from (a race, a forbidden action). A session that cannot
     * be read now fails later, when those requests are read.
     */
    private suspend fun startWatching(actor: ActorContext): HarnessTimestamp {
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
     * not a lost race): code decides (AGENTS.md rule 2), so the action failed with [REQUEST_FAILED].
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

    /**
     * Publishes the step's event for this actor with the write behind it ([writeOf]): the id comes from the configured
     * source, t0 from the request the actor's page sent during [performed] ([watchStart] is set when its requests were
     * watched), the publish time is kept apart.
     */
    private suspend fun emit(
        actor: ActorContext,
        spec: EmitSpec,
        performed: Performed,
        templates: TemplateContext,
        watchStart: HarnessTimestamp?,
    ): Emitted {
        val started = clock.now()
        val source = spec.idSource ?: run.campaign.target.idSource(spec.event)
        val resolution = services.objectIds.read(source, actor.session, performed.outcome, templates)
        val write = writeOf(actor, spec.request ?: raceSpec(actor.step)?.request, performed.startedAt, watched = watchStart != null)
        val event =
            run.bus.publish(spec.event, resolution.objectId, actor.agentId, EventOrigin(performed.startedAt, write.write), run.pass)
        if (actor.step.phase == StepPhase.SETUP) run.setupEvents += event
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
            emitDetail(event, resolution, write.note),
            actor.correlationId,
            if (failed) Tally.FAIL else Tally.PASS,
        )
        return Emitted(event, resolution.problem)
    }

    /**
     * The write behind an event (Faza 24.10): the first request of the action ([since] on) that the target accepted and
     * that matches [pattern] (`emits.request`, in a race the race's request); without a pattern the action's first
     * accepted mutating request, exact only when it was the only one. None, or requests that could not be read, leave
     * the publish as t0, and the note says so.
     */
    private suspend fun writeOf(
        actor: ActorContext,
        pattern: RequestPattern?,
        since: HarnessTimestamp,
        watched: Boolean,
    ): WriteLookup {
        val fromPublish = "latency is measured from this publish"
        if (!watched) return WriteLookup(null, "the actor's requests were not watched; $fromPublish")
        val accepted =
            try {
                actor.session.mutations(since).filter { it.status < RaceEvidence.ACCEPTED_BELOW }
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                return WriteLookup(null, "the actor's requests could not be read (${e::class.simpleName}); $fromPublish")
            }
        if (pattern != null) {
            val match =
                accepted.firstOrNull { pattern.matches(it.method, it.path) }
                    ?: return WriteLookup(null, "no accepted request matched `${pattern.describe()}`; $fromPublish")
            return WriteLookup(EventWrite(match.describe(), match.at, exact = true), "written by ${match.describe()}")
        }
        val first = accepted.firstOrNull() ?: return WriteLookup(null, "the page sent no accepted request to the site; $fromPublish")
        if (accepted.size == 1) return WriteLookup(EventWrite(first.describe(), first.at, exact = true), "written by ${first.describe()}")
        return WriteLookup(
            EventWrite(first.describe(), first.at, exact = false),
            "written by ${first.describe()} or a later one of ${accepted.size} accepted requests (emits.request names the write)",
        )
    }

    /** When the change behind [event] reached the target, as a receiver's latency is measured (see [writeOf]). */
    private fun eventTime(event: PublishedEvent): EventTime {
        val origin = event.origin ?: return EventTime.at(event.publishedAt, "the publish")
        val write = origin.write
        return when {
            write == null -> EventTime(event.publishedAt, origin.actionStartedAt, event.publishedAt, "the publish (no write seen)")
            write.exact -> EventTime.at(write.at, "the write ${write.request}")
            else -> EventTime(write.at, write.at, event.publishedAt, "the first of several writes, ${write.request}")
        }
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
            put("published_at", event.publishedAt.wall.toString())
            put("write", event.origin?.write?.request)
            put("sequence", event.sequence)
            put("scenario_step", actor.step.id)
            put("id_source", resolution.source)
        }.toString()

    private fun emitDetail(
        event: PublishedEvent,
        resolution: ObjectIdResolution,
        write: String,
    ): String =
        buildString {
            append("${event.name} id=${event.objectId ?: "-"}")
            resolution.source?.let { append(" ($it)") }
            resolution.problem?.let { append("; $ID_UNAVAILABLE: $it") }
            resolution.note?.let { append("; $it") }
            append("; ").append(write)
        }

    // --- receivers watching ahead ---------------------------------------------------------------------------------

    /**
     * Before this step's actors act, the receivers of the event it emits start watching their own pages for the text of
     * their reception check (Faza 24.10): the first `visible_text` of each later step that waits for the event, rendered
     * for each receiver (not the emitters themselves). Their pages then time the text as it appears, however long the
     * emitter's agent keeps working after the write, and a text already on the page shows that seeing it would prove
     * nothing ([TextWatch.WasThere]). A text that names the event's own object (`{last_id}`, `{event.<it>.id}`) cannot be
     * known before the object exists: such a receiver checks after the event, as before, and its latency is an upper
     * bound when the text is there at the first look.
     */
    private suspend fun armReceivers(
        step: ScenarioStep,
        emitters: List<Identity>,
    ) {
        val event = step.emits?.event ?: return
        val base = step.id.removeSuffix(DefaultCampaignRunner.SWAP_SUFFIX)
        val pass = step.id.removePrefix(base)
        val emitting = emitters.map { it.agentId }.toSet()
        val arms =
            run.campaign.allSteps
                .dropWhile { it.id != base }
                .drop(1)
                .filter { it.waitFor?.event == event }
                .flatMap { receiver ->
                    val text = receptionSpecs(receiver).firstNotNullOfOrNull { it as? AssertionSpec.VisibleText }?.text
                    if (text == null) return@flatMap emptyList()
                    resolver
                        .resolve(receiver.actors, run.activeIdentities())
                        .filter { it.agentId !in emitting }
                        .map { Triple(receiver.id + pass, it, text) }
                }
        if (arms.isEmpty()) return
        coroutineScope {
            arms
                .map { (stepId, identity, text) ->
                    async(services.diagnostics.of(run.runId, identity.agentId)) { arm(stepId, identity, text) }
                }.awaitAll()
        }
    }

    private suspend fun arm(
        stepId: String,
        identity: Identity,
        template: String,
    ) {
        val text =
            try {
                // The receivers read what the emitter writes now, in the pass now running.
                services.renderer.render(template, templateContext(identity, lastId = null, pass = run.pass))
            } catch (_: TemplateException) {
                // It names the event's own object, which does not exist yet.
                return
            }
        val session = run.sessions[identity.agentId] ?: return
        val reading =
            try {
                session.watchText(stepId, text)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                logger.debug { "${identity.agentId}: could not start watching for \"$text\" (${e.message})" }
                return
            }
        if (reading == TextWatch.NotYet ||
            reading == TextWatch.WasThere
        ) {
            run.armed(stepId, identity.agentId, RunState.ArmedWatch(stepId, text, reading))
        }
    }

    /** What [armed] saw; a text on the page before the write stays so, whatever the page did since. */
    private suspend fun read(
        actor: ActorContext,
        armed: RunState.ArmedWatch,
    ): TextWatch {
        val now =
            try {
                actor.session.stopTextWatch(armed.key)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                TextWatch.Lost
            }
        return if (armed.reading == TextWatch.WasThere) TextWatch.WasThere else now
    }

    /** Ends the watch [actor] started for its step, when it will not check the text after all. */
    private suspend fun releaseWatch(actor: ActorContext) {
        run.takeWatch(actor.step.id, actor.agentId)?.let { stopQuietly(actor.session, it) }
    }

    /** Ends the watches for [stepId] of receivers that do not run it now (they failed since the watch began). */
    private suspend fun releaseWatches(
        stepId: String,
        keep: Set<AgentId>,
    ) {
        run.watchersOf(stepId).filterNot { it in keep }.forEach { agentId ->
            val watch = run.takeWatch(stepId, agentId) ?: return@forEach
            run.sessions[agentId]?.let { stopQuietly(it, watch) }
        }
    }

    private suspend fun stopQuietly(
        session: BrowserSession,
        watch: RunState.ArmedWatch,
    ) {
        try {
            session.stopTextWatch(watch.key)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
        }
    }

    // --- assertions -----------------------------------------------------------------------------------------------

    private suspend fun verifyActor(
        actor: ActorContext,
        stepId: StepId,
        specs: List<AssertionSpec>,
        templates: TemplateContext,
        time: EventTime?,
        watch: WatchedText? = null,
        earlierDelivery: String? = null,
    ): Verification {
        if (specs.isEmpty()) return Verification(emptyList(), error = false)
        val input =
            AssertionInput(
                run.runId,
                stepId,
                actor.step.id,
                actor.agentId,
                actor.session,
                templates,
                time,
                watch,
                earlierDelivery,
                apiOrigin = run.campaign.apiOriginInUse,
            )
        return try {
            val records = services.verify.verifyActor(specs, input)
            records.filter { it.verdict == Verdict.FAILED }.forEach { run.tally.assertionFailed(actor.agentId) }
            repeat(records.count { it.verdict == Verdict.INCONCLUSIVE }) { run.tally.assertionInconclusive() }
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
        // The group verdict comes after every actor acted: `{last_id}` is the object the winner emitted when the step emits,
        // else the one the racers waited for; nothing of another step.
        val own = step.emits?.event ?: step.waitFor?.event
        val event =
            own?.let { name ->
                run.bus
                    .latest(name)
                    ?.takeIf { it.sequence > run.eventCursor(name) }
            }
        val templates = templateContext(null, event?.objectId, passOf(step, event))
        val input = AssertionInput(run.runId, stepId, step.id, null, null, templates, eventTime = null)
        val actorResults =
            results.map {
                ActorResult(
                    agentId = it.identity.agentId,
                    succeeded = it.race?.succeeded == true,
                    summary = it.outcome?.summary ?: it.failureKey.orEmpty(),
                    race = it.race,
                    lostRace = it.lostRace,
                    session = run.sessions[it.identity.agentId],
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
        val undecided = records.count { it.verdict == Verdict.INCONCLUSIVE }
        repeat(undecided) { run.tally.assertionInconclusive() }
        val failed = failures > 0
        evidence.step(
            run,
            null,
            step.id,
            StepKind.SYSTEM,
            "verify_group ${specs.joinToString(",") { it.type }}",
            started,
            when {
                failed -> StepStatus.FAILED

                // Neither proven nor refuted: the group check could not decide (Faza 24.12).
                undecided > 0 -> StepStatus.SKIPPED

                else -> StepStatus.PASSED
            },
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
        val undecided = failureKey == null && acted.checks.any { it.inconclusive }
        val result =
            when {
                failureKey != null -> failureKey
                undecided -> INCONCLUSIVE
                lost != null -> LOST_RACE
                isExpectedRefusal(actor.step, outcome) -> FailureReason.PERMISSION_DENIED.key
                else -> "ok"
            }
        board.update(actor.agentId, state, actor.step.id, "$result: ${outcome.summary}")
        val task =
            when {
                failureKey != null && blocked -> TaskState.BLOCKED

                // Not proven: the board shows it as not passed, its detail says why (the run is not PASSED either).
                failureKey != null || undecided -> TaskState.FAILED

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

    /**
     * [lastId] is the step's own object for this actor: the one it emitted or the one it waited for (see [runActor]);
     * [pass] the execution its own event belongs to ([passOf]).
     */
    private fun templateContext(
        identity: Identity?,
        lastId: String?,
        pass: Int,
    ): TemplateContext =
        TemplateContext(
            lastId = lastId,
            self = identity?.let(::selfFields).orEmpty(),
            eventIds = latestEventIds(),
            testers = testers,
            pass = run.passMark(pass),
        )

    /**
     * `{pass}` of [step] (see [az.petek.campaign.domain.Placeholder.Pass]): the pass now running when the step emits,
     * since it writes its event now; else the pass of the event it waited for ([waited], a setup event of the first
     * wave read in a later one); else the pass now running.
     */
    private fun passOf(
        step: ScenarioStep,
        waited: PublishedEvent?,
    ): Int = if (step.emits != null) run.pass else waited?.pass ?: run.pass

    /**
     * `{tester.<role>.<n>.<field>}` (Faza 18): every tester of the run by role and 1-based agent order, with only the
     * fields a card may name. Computed once; the identities do not change during a run.
     */
    private val testers: Map<String, Map<String, String>> by lazy {
        run.identities
            .sortedBy { it.agentId }
            .groupBy { it.role }
            .flatMap { (role, members) ->
                members.mapIndexed { i, identity ->
                    "${role.key}.${i + 1}" to
                        mapOf("name" to identity.displayName, "email" to identity.email)
                }
            }.toMap()
    }

    /** `{event.<name>.id}`: the object id of the newest event of every name the campaign emits. */
    private fun latestEventIds(): Map<String, String> =
        buildMap {
            run.eventNames.forEach { name ->
                run.bus
                    .latest(name)
                    ?.takeIf { it.sequence > run.eventCursor(name) }
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
        val share: ActorShare,
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

    /** A request a forbidden-action step expects the site to refuse: [method] and [path] (no query) as its assertion names them. */
    private class ForbiddenRequest(
        val method: String,
        val path: String,
    ) {
        fun matches(
            sentMethod: String,
            sentPath: String,
        ): Boolean = sentMethod.equals(method, ignoreCase = true) && sentPath.trimEnd('/') == path.trimEnd('/')
    }

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

    /** The write behind an event, when the actor's requests showed it, and what the emit record says about it. */
    private class WriteLookup(
        val write: EventWrite?,
        val note: String,
    )

    private data class Verification(
        val records: List<AssertionRecord>,
        val error: Boolean,
    ) {
        val failed: Boolean get() = error || records.any { it.verdict == Verdict.FAILED }

        /** A check ran but could not decide (Faza 24.12): not a failure, but the actor's step is not proven either. */
        val inconclusive: Boolean get() = records.any { it.verdict == Verdict.INCONCLUSIVE }
    }

    companion object {
        private const val TOO_MANY_REQUESTS = 429
        const val NOT_RECEIVED = "not_received"

        /** A receiver whose wave had no tester for the step that emits its event (Faza 24.7). */
        const val EMITTER_ABSENT = "emitter_absent"
        const val TEMPLATE_ERROR = "template_error"
        const val ID_UNAVAILABLE = "id_unavailable"
        const val ASSERTION_FAILED = "assertion_failed"
        const val VERIFICATION_ERROR = "verification_error"

        /** Detail key of an action that lost a race: an expected outcome, not a failure (see reporting's FailureKeys). */
        const val LOST_RACE = "lost_race"

        /** Result of an actor whose checks ran but could not decide (Faza 24.12): neither passed nor failed. */
        const val INCONCLUSIVE = "inconclusive"

        /**
         * Failure key of a race action whose agent claimed success while the target turned down the actor's own
         * request (e.g. a 500 or 403 on the approval): the request, not the agent, decides (AGENTS.md rule 2).
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

        /** The same testers by role, department and registration, whatever place `n` names among them. */
        fun ActorExpression.anyPlace(): ActorExpression = copy(selectors = selectors.map { it.copy(nth = null) })
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
