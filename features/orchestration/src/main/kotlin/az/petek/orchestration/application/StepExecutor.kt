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
import az.petek.verification.application.VerifyStepUseCase
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration

/** What one actor achieved in one scenario step. [failureKey] is null when the actor passed the step. */
internal data class ActorStepResult(
    val identity: Identity,
    /** Null when the action never ran (awaited event missing, template error). */
    val outcome: ActionOutcome?,
    val failureKey: String?,
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
 * `wait_for` on the bus -> reception checks -> render templates -> (start barrier) -> perform under the watchdog ->
 * `emits` -> the remaining assertions. All actors of a step run concurrently; `only_one_succeeds` is judged once
 * all of them are done.
 *
 * Reception checks: in a step with `wait_for`, `visible_text` (and the `latency_max` that reads its latency) are
 * evaluated right after the event arrives, before the actor's own action. Their deadline is t0 + `within`, so
 * evaluating them after a multi-second LLM action would measure the agent instead of the target's delivery
 * (design decision 3: t1 - t0 is the real delivery latency). Every other assertion runs after the action.
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

    suspend fun execute(step: ScenarioStep): StepResult {
        board.stepStarted(step.id)
        recordSkippedFailedActors(step)
        val chosen = resolver.resolve(step.actors, run.activeIdentities())
        if (chosen.isEmpty()) {
            evidence.system(run, null, "skip", StepStatus.SKIPPED, "no active actor matches '${step.actors.raw}'", step.id)
            return StepResult(step, emptyList(), groupFailed = false)
        }
        val barrier = if (step.parallel) StartBarrier(chosen.size) else null
        val results =
            coroutineScope {
                chosen
                    .map { identity ->
                        async(services.diagnostics.of(run.runId, identity.agentId)) { runActor(step, identity, barrier) }
                    }.awaitAll()
            }
        return StepResult(step, results, groupFailed = verifyGroup(step, results))
    }

    private suspend fun recordSkippedFailedActors(step: ScenarioStep) {
        resolver
            .resolve(step.actors, run.identities)
            .filter { run.isFailed(it.agentId) }
            .forEach {
                val reason = run.failureReason(it.agentId) ?: "failed"
                evidence.system(run, it.agentId, "skip", StepStatus.SKIPPED, "agent failed earlier ($reason)", step.id)
            }
    }

    private suspend fun runActor(
        step: ScenarioStep,
        identity: Identity,
        barrier: StartBarrier?,
    ): ActorStepResult {
        var arrived = false
        try {
            val actor = ActorContext(step, identity, run.sessions.getValue(identity.agentId), run.agents.getValue(identity.agentId))
            val waited =
                step.waitFor?.let { spec ->
                    when (val result = awaitEvent(actor, spec)) {
                        is WaitResult.Received -> result.waited
                        is WaitResult.TimedOut -> return notReceived(actor, spec, result.startedAt)
                    }
                }
            val reception = waited?.let { receive(actor, it) }
            val templates = templateContext(identity, waited?.event?.objectId)
            val action =
                try {
                    render(step.action, templates)
                } catch (e: TemplateException) {
                    return templateFailed(actor, e)
                }
            if (barrier != null) {
                barrier.arrive()
                arrived = true
                barrier.awaitOpen()
            }
            val outcome = perform(actor, action, templates)
            val emitted = if (outcome.succeeded) step.emits?.let { emit(actor, it, outcome, templates) } else null
            val lastId = emitted?.event?.objectId ?: waited?.event?.objectId
            val checks =
                verifyActor(actor, actor.stepId, afterActionSpecs(step), templateContext(identity, lastId), waited?.event?.t0)
            return conclude(actor, outcome, emitted, listOfNotNull(reception, checks))
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
        val waitStepId =
            evidence.step(
                run,
                actor.agentId,
                actor.step.id,
                StepKind.WAIT,
                "wait_for ${spec.event}",
                startedAt,
                StepStatus.FAILED,
                "$NOT_RECEIVED: ${spec.event} was not published within ${spec.timeout}",
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
    ) = services.recorder.receipt(EventReceipt(event.eventId, run.runId, actor.agentId, received, t1, latencyMs))

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
        evidence.step(
            run,
            actor.agentId,
            actor.step.id,
            kindOf(actor.step.action),
            describe(actor.step.action),
            clock.now(),
            StepStatus.FAILED,
            "$TEMPLATE_ERROR: ${error.message}",
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
        board.update(actor.agentId, AgentState.IDLE, actor.step.id, "$TEMPLATE_ERROR: ${error.message}")
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

    private suspend fun perform(
        actor: ActorContext,
        action: StepAction,
        templates: TemplateContext,
    ): ActionOutcome {
        val description = describe(action)
        board.update(actor.agentId, AgentState.WORKING, actor.step.id, description)
        val started = clock.now()
        val outcome = if (action is StepAction.None) NOTHING_TO_DO else execute(actor, action, templates)
        evidence.step(
            run,
            actor.agentId,
            actor.step.id,
            kindOf(action),
            description,
            started,
            statusOf(outcome),
            detailOf(outcome),
            actor.correlationId,
            tallyOf(actor.step, outcome),
            stepId = actor.stepId,
        )
        if (outcome.status == ActionStatus.ERROR || (outcome.status == ActionStatus.BLOCKED && !isRefusal(outcome))) {
            evidence.screenshot(run, actor.stepId, actor.agentId, actor.session)
        }
        return outcome
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
        val input = AssertionInput(run.runId, stepId, step.id, null, null, templateContext(null, null), null)
        val actorResults =
            results.map {
                ActorResult(it.identity.agentId, it.outcome?.succeeded == true, it.outcome?.summary ?: it.failureKey.orEmpty())
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
        actor: ActorContext,
        outcome: ActionOutcome,
        emitted: Emitted?,
        checks: List<Verification>,
    ): ActorStepResult {
        val failureKey =
            when {
                !outcome.succeeded && !isExpectedRefusal(actor.step, outcome) -> {
                    outcome.failureReason?.key ?: outcome.status.name.lowercase()
                }

                emitted?.problem != null -> {
                    ID_UNAVAILABLE
                }

                checks.any { it.error } -> {
                    VERIFICATION_ERROR
                }

                checks.any { it.failed } -> {
                    ASSERTION_FAILED
                }

                else -> {
                    null
                }
            }
        val state = if (outcome.status == ActionStatus.BLOCKED && !isRefusal(outcome)) AgentState.BLOCKED else AgentState.IDLE
        val lastAction = "${failureKey ?: "ok"}: ${outcome.summary}"
        board.update(actor.agentId, state, actor.step.id, lastAction)
        return ActorStepResult(actor.identity, outcome, failureKey)
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun templateContext(
        identity: Identity?,
        lastIdOverride: String?,
    ): TemplateContext =
        TemplateContext(
            lastId = lastIdOverride ?: run.bus.latestAny()?.objectId,
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
    val clock: HarnessClock,
    val ids: IdGenerator,
    val diagnostics: DiagnosticContext,
)
