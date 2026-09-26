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

import az.petek.core.ids.AgentId
import az.petek.core.ids.EventId
import az.petek.core.ids.RunId
import az.petek.core.model.Role
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.DashboardUpdate.AgentUpdated
import az.petek.dashboard.domain.DashboardUpdate.AgentsPlanned
import az.petek.dashboard.domain.DashboardUpdate.ArtifactRecorded
import az.petek.dashboard.domain.DashboardUpdate.AssertionRecorded
import az.petek.dashboard.domain.DashboardUpdate.EventRecorded
import az.petek.dashboard.domain.DashboardUpdate.FindingRecorded
import az.petek.dashboard.domain.DashboardUpdate.Message
import az.petek.dashboard.domain.DashboardUpdate.PlanReady
import az.petek.dashboard.domain.DashboardUpdate.ReceiptRecorded
import az.petek.dashboard.domain.DashboardUpdate.RunCreated
import az.petek.dashboard.domain.DashboardUpdate.RunEnded
import az.petek.dashboard.domain.DashboardUpdate.RunStarted
import az.petek.dashboard.domain.DashboardUpdate.ScenarioStepStarted
import az.petek.dashboard.domain.DashboardUpdate.StepRecorded
import az.petek.dashboard.domain.DashboardUpdate.TaskUpdated
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.RunOutcome
import java.time.Instant

/**
 * The dashboard's state and its pure reducer: [apply] never mutates, it returns the next state (or this very
 * instance when the update does not concern the shown run), so the whole dashboard logic is testable without threads,
 * clocks or servers.
 *
 * Rules:
 * - **Run scope** — see [DashboardUpdate]. A fresh board keeps [version] and the timeline sequence growing, so views
 *   never mistake a new run's entries for old ones.
 * - **Agent state** is the orchestrator's ([AgentUpdated], [RunStarted]); evidence never changes it. A transition
 *   into FAILED or BLOCKED is written to the timeline with its reason.
 * - **Last action** is the newest of: a recorded step (its action), or an orchestrator status whose action text
 *   changed since that agent's previous status (the board repeats the old text in every status; a repeat must not
 *   hide a newer recorded step).
 * - **Counters**: harness housekeeping (SYSTEM steps) that passed is not counted as a passed step, but every FAILED,
 *   ERROR or BLOCKED record is a failed step. A failed step or a failed assertion counts as a failure of its agent.
 * - **Screenshots**: the agent's latest SCREENSHOT artifact, attributed through the artifact path
 *   (`<run>/<agent>/<file>`, see `ArtifactStore`).
 * - **Bounds**: the run timeline keeps the newest [TIMELINE_LIMIT] entries, each agent's own timeline the newest
 *   [AGENT_TIMELINE_LIMIT], the findings list the newest [FINDINGS_LIMIT] (the counter counts all), the event list the
 *   newest [EVENTS_LIMIT].
 * - **Orchestrator**: the latest plan and the latest task report per (step, agent); [orchestratorVersion] grows only
 *   when the plan, a task, an event or the run header changed, so the orchestrator screen is not redrawn for every
 *   agent action.
 *
 * Instances compare by identity: states are large, and every applied update produces a new [version] anyway.
 */
class DashboardState private constructor(
    /** Grows by one with every update that changed something. */
    val version: Long,
    private val run: RunTrack?,
    private val agents: Map<AgentId, AgentTrack>,
    private val timeline: List<TimelineEntry>,
    private val agentTimelines: Map<AgentId, List<TimelineEntry>>,
    private val findings: List<FindingView>,
    private val tally: Tally,
    private val eventNames: Map<EventId, String>,
    private val nextSeq: Long,
    /** Grows by one with every update that changed what the orchestrator screen shows. */
    val orchestratorVersion: Long,
    private val plan: RunPlanView?,
    /** Latest task report per plan step, then per agent. */
    private val tasks: Map<String, Map<AgentId, TaskStateView>>,
    private val events: List<EventView>,
) {
    /** The run on the board, or null before any run was seen. */
    val runId: RunId? get() = run?.runId

    /** Directory of the shown run's report, once the run has ended with one. */
    val reportPath: String? get() = run?.reportPath

    fun apply(update: DashboardUpdate): DashboardState {
        val next =
            when (update) {
                is RunCreated -> onRunCreated(update)
                is AgentsPlanned -> scoped(update.runId, update.at)?.onAgentsPlanned(update)
                is RunStarted -> onRunStarted(update)
                is AgentUpdated -> onAgentUpdated(update)
                is ScenarioStepStarted -> onScenarioStepStarted(update)
                is Message -> onMessage(update)
                is RunEnded -> onRunEnded(update)
                is StepRecorded -> scoped(update.record.runId, update.at)?.onStep(update)
                is ArtifactRecorded -> scoped(update.record.runId, update.at)?.onArtifact(update)
                is EventRecorded -> scoped(update.record.runId, update.at)?.onEvent(update)
                is ReceiptRecorded -> scoped(update.record.runId, update.at)?.onReceipt(update)
                is AssertionRecorded -> scoped(update.record.runId, update.at)?.onAssertion(update)
                is FindingRecorded -> scoped(update.record.runId, update.at)?.onFinding(update)
                is PlanReady -> onPlanReady(update)
                is TaskUpdated -> scoped(update.task.runId, update.at)?.onTask(update)
            } ?: return this
        val orchestrated = next.plan !== plan || next.tasks !== tasks || next.events !== events || next.run !== run
        return next.copy(version = version + 1, orchestratorVersion = orchestratorVersion + if (orchestrated) 1 else 0)
    }

    fun applyAll(updates: Iterable<DashboardUpdate>): DashboardState = updates.fold(this) { state, update -> state.apply(update) }

    /** The page's view at [now]; [now] only drives the live elapsed time of a running run. */
    fun snapshot(now: HarnessTimestamp): DashboardSnapshot {
        val cards = agents.values.map { it.card }.sortedBy { it.agentId.index }
        return DashboardSnapshot(
            version = version,
            generatedAt = now.wall,
            run = runView(now),
            counters = counters(cards),
            agents = cards,
            timeline = timeline,
            findings = findings,
        )
    }

    /** The orchestrator screen's view at [now]: plan, task matrix and events of the shown run. */
    fun orchestrator(now: HarnessTimestamp): OrchestratorSnapshot {
        val refs = agents.values.map { AgentRef(it.card.agentId, it.card.displayName, it.card.role) }.sortedBy { it.agentId.index }
        val steps = plan?.steps.orEmpty()
        val order = steps.withIndex().associate { (index, step) -> step.id to index }
        val reported =
            tasks.values
                .flatMap { it.values }
                .sortedWith(compareBy<TaskStateView>({ order[it.stepId] ?: Int.MAX_VALUE }, { it.stepId }, { it.agentId.index }))
        val cells = steps.flatMap { step -> step.agentIds.map { tasks[step.id]?.get(it)?.state ?: TaskState.PENDING } }
        val byState = cells.groupingBy { it }.eachCount()
        return OrchestratorSnapshot(
            version = orchestratorVersion,
            generatedAt = now.wall,
            run = runView(now),
            plan = plan,
            agents = refs,
            tasks = reported,
            taskCounts = TaskState.entries.associateWith { byState[it] ?: 0 },
            events = events,
        )
    }

    /** The agent's card and its own newest timeline entries; null for an agent the board has never seen. */
    fun agentDetail(agentId: AgentId): AgentDetail? = agents[agentId]?.let { AgentDetail(it.card, agentTimelines[agentId].orEmpty()) }

    /**
     * An empty board that continues this one's versions and timeline order, e.g. to replay a recorded run in place of
     * the live one without views mistaking the replay for something they have already shown.
     */
    fun cleared(): DashboardState = EMPTY.copy(version = version + 1, nextSeq = nextSeq, orchestratorVersion = orchestratorVersion + 1)

    // --- run scope ------------------------------------------------------------------------------------------------

    private fun scoped(
        runId: RunId,
        at: HarnessTimestamp,
    ): DashboardState? {
        val current = run
        return when {
            current == null -> copy(run = RunTrack.started(runId, at))
            current.runId == runId -> this
            current.ended -> fresh(runId, at)
            else -> null
        }
    }

    /** Authoritative scope: the named run is on the board afterwards, whatever was there before. */
    private fun claimed(
        runId: RunId,
        at: HarnessTimestamp,
    ): DashboardState =
        when (run?.runId) {
            runId -> this
            null -> copy(run = RunTrack.started(runId, at))
            else -> fresh(runId, at)
        }

    private fun fresh(
        runId: RunId,
        at: HarnessTimestamp,
    ): DashboardState =
        EMPTY.copy(version = version, nextSeq = nextSeq, orchestratorVersion = orchestratorVersion, run = RunTrack.started(runId, at))

    private fun onRunCreated(update: RunCreated): DashboardState {
        val record = update.run
        return claimed(record.runId, update.at).withRun {
            it.copy(campaignName = record.campaignName, target = record.target, startedAt = record.startedAt)
        }
    }

    private fun onRunStarted(update: RunStarted): DashboardState =
        update.agents.fold(claimed(update.runId, update.at)) { state, status -> state.withStatus(status, update.at) }

    private fun onRunEnded(update: RunEnded): DashboardState? {
        val current = run
        if (current != null && current.runId != update.runId) return null
        val base = current?.let { this } ?: copy(run = RunTrack.started(update.runId, update.at))
        return base
            .withRun { it.copy(ended = true, outcome = update.outcome, durationMs = update.durationMs, reportPath = update.reportPath) }
            .log(
                at = update.at.wall,
                agentId = null,
                kind = TimelineKind.MESSAGE,
                status = if (update.outcome == RunOutcome.PASSED) TimelineStatus.OK else TimelineStatus.FAIL,
                text = TimelineTexts.runEnded(update.outcome),
            )
    }

    private fun onScenarioStepStarted(update: ScenarioStepStarted): DashboardState =
        withRun { it.copy(currentScenarioStep = update.scenarioStep) }
            .log(
                at = update.at.wall,
                agentId = null,
                kind = TimelineKind.MESSAGE,
                status = TimelineStatus.INFO,
                text = TimelineTexts.scenarioStepStarted(update.scenarioStep),
                scenarioStep = update.scenarioStep,
            )

    private fun onMessage(update: Message): DashboardState =
        log(update.at.wall, agentMentionedIn(update.text), TimelineKind.MESSAGE, TimelineStatus.INFO, TimelineTexts.line(update.text))

    // --- agents ---------------------------------------------------------------------------------------------------

    private fun onAgentsPlanned(update: AgentsPlanned): DashboardState =
        update.agents.fold(this) { state, profile ->
            state.withCard(profile.agentId) {
                it.copy(
                    displayName = profile.displayName,
                    role = profile.role,
                    department = profile.department,
                    registration = profile.registration,
                )
            }
        }

    private fun onAgentUpdated(update: AgentUpdated): DashboardState = withStatus(update.status, update.at)

    private fun withStatus(
        status: AgentStatus,
        at: HarnessTimestamp,
    ): DashboardState {
        val track = agents[status.agentId] ?: AgentTrack.unknown(status.agentId)
        val before = track.card
        val reported = status.lastAction?.let { TimelineTexts.line(it, TimelineTexts.ACTION_CHARS) }
        val newAction = reported?.takeIf { it != track.boardAction }
        val troubled = status.state == AgentState.FAILED || status.state == AgentState.BLOCKED
        val enteredTrouble = troubled && before.state != status.state
        val card =
            before.copy(
                displayName = status.displayName.ifBlank { before.displayName },
                role = Role.fromKey(status.role) ?: before.role,
                state = status.state,
                scenarioStep = status.scenarioStep ?: before.scenarioStep,
                lastAction = newAction ?: before.lastAction,
                lastActionAt = if (newAction != null) status.updatedAt.wall else before.lastActionAt,
                lastFailureReason = if (troubled && newAction != null) newAction else before.lastFailureReason,
            )
        val next = copy(agents = agents + (status.agentId to AgentTrack(card, reported ?: track.boardAction)))
        if (!enteredTrouble) return next
        return next.log(
            at = at.wall,
            agentId = status.agentId,
            kind = TimelineKind.MESSAGE,
            status = TimelineStatus.FAIL,
            text = TimelineTexts.stateChange(status.state, card.lastFailureReason ?: card.lastAction),
            scenarioStep = card.scenarioStep,
        )
    }

    // --- evidence -------------------------------------------------------------------------------------------------

    private fun onStep(update: StepRecorded): DashboardState {
        val record = update.record
        val failed = record.status in FAILED_STEPS
        val housekeeping = record.kind == StepKind.SYSTEM
        val counted =
            copy(
                tally =
                    tally.copy(
                        stepsPassed = tally.stepsPassed + if (!housekeeping && record.status == StepStatus.PASSED) 1 else 0,
                        stepsFailed = tally.stepsFailed + if (failed) 1 else 0,
                    ),
            )
        val agentId = record.agentId
        val withAgent =
            if (agentId == null || (housekeeping && !failed)) counted else counted.withCard(agentId) { it.after(record, failed) }
        val reason = record.llmReason?.takeIf { record.kind == StepKind.DO && it.isNotBlank() }
        val withDialog =
            if (reason == null) {
                withAgent
            } else {
                withAgent.log(
                    record.startedAt,
                    agentId,
                    TimelineKind.DIALOG,
                    TimelineStatus.INFO,
                    TimelineTexts.line(reason),
                    record.scenarioStep,
                )
            }
        return withDialog.log(
            at = record.endedAt,
            agentId = agentId,
            kind = TimelineKind.STEP,
            status = stepStatus(record, housekeeping),
            text = TimelineTexts.step(record),
            scenarioStep = record.scenarioStep.takeUnless { housekeeping },
        )
    }

    private fun AgentCard.after(
        record: StepRecord,
        failed: Boolean,
    ): AgentCard {
        val scenario = record.kind != StepKind.SYSTEM
        return copy(
            scenarioStep = if (scenario) record.scenarioStep else scenarioStep,
            lastAction = TimelineTexts.line(record.action, TimelineTexts.ACTION_CHARS),
            lastActionAt = record.endedAt,
            actionsDone = actionsDone + if (scenario && record.status != StepStatus.SKIPPED) 1 else 0,
            failures = failures + if (failed) 1 else 0,
            lastFailureReason = if (failed) TimelineTexts.stepFailure(record) else lastFailureReason,
        )
    }

    private fun stepStatus(
        record: StepRecord,
        housekeeping: Boolean,
    ): TimelineStatus =
        when (record.status) {
            StepStatus.PASSED -> if (housekeeping) TimelineStatus.INFO else TimelineStatus.OK
            StepStatus.SKIPPED -> TimelineStatus.INFO
            StepStatus.FAILED, StepStatus.ERROR, StepStatus.BLOCKED -> TimelineStatus.FAIL
        }

    private fun onArtifact(update: ArtifactRecorded): DashboardState? {
        val record = update.record
        if (record.type != ArtifactType.SCREENSHOT) return null
        val owner = ownerOf(record) ?: return null
        return withCard(owner) { it.copy(lastScreenshotArtifactId = record.artifactId) }
    }

    private fun onEvent(update: EventRecorded): DashboardState? {
        val record = update.record
        if (record.eventId in eventNames) return null
        val view = EventView(record.eventId, record.name, record.emitter, record.objectId, record.t0, emptyList())
        return copy(
            tally = tally.copy(events = tally.events + 1),
            eventNames = eventNames + (record.eventId to record.name),
            events = (listOf(view) + events).take(EVENTS_LIMIT),
        ).log(record.t0, record.emitter, TimelineKind.EVENT, TimelineStatus.OK, TimelineTexts.event(record))
    }

    private fun onReceipt(update: ReceiptRecorded): DashboardState? {
        val record = update.record
        val shown = events.indexOfFirst { it.eventId == record.eventId }
        val event = events.getOrNull(shown)
        if (event != null && event.receipts.any { it.receiver == record.receiver }) return null
        val withReceipt =
            if (event == null) {
                events
            } else {
                val receipts =
                    (
                        event.receipts +
                            ReceiptView(
                                record.receiver,
                                record.received,
                                record.latencyMs,
                            )
                    ).sortedBy { it.receiver.index }
                events.toMutableList().also { it[shown] = event.copy(receipts = receipts) }
            }
        val counted =
            if (record.received) {
                tally.copy(receiptsReceived = tally.receiptsReceived + 1)
            } else {
                tally.copy(receiptsMissing = tally.receiptsMissing + 1)
            }
        return copy(tally = counted, events = withReceipt).log(
            at = record.t1 ?: update.at.wall,
            agentId = record.receiver,
            kind = TimelineKind.RECEIPT,
            status = if (record.received) TimelineStatus.OK else TimelineStatus.FAIL,
            text = TimelineTexts.receipt(record, eventNames[record.eventId] ?: record.eventId.value),
        )
    }

    private fun onAssertion(update: AssertionRecorded): DashboardState {
        val record = update.record
        val text = TimelineTexts.assertion(record)
        val counted =
            copy(
                tally =
                    when (record.verdict) {
                        Verdict.PASSED -> tally.copy(assertionsPassed = tally.assertionsPassed + 1)
                        Verdict.FAILED -> tally.copy(assertionsFailed = tally.assertionsFailed + 1)
                        Verdict.SKIPPED, Verdict.NOT_APPLICABLE -> tally.copy(assertionsSkipped = tally.assertionsSkipped + 1)
                    },
            )
        val agentId = record.agentId
        val withAgent =
            if (agentId == null || record.verdict != Verdict.FAILED) {
                counted
            } else {
                counted.withCard(agentId) { it.copy(failures = it.failures + 1, lastFailureReason = text) }
            }
        return withAgent.log(
            at = update.at.wall,
            agentId = agentId,
            kind = TimelineKind.ASSERTION,
            status =
                when (record.verdict) {
                    Verdict.PASSED -> TimelineStatus.OK
                    Verdict.FAILED -> TimelineStatus.FAIL
                    Verdict.SKIPPED, Verdict.NOT_APPLICABLE -> TimelineStatus.INFO
                },
            text = text,
            scenarioStep = record.scenarioStep,
        )
    }

    private fun onFinding(update: FindingRecorded): DashboardState {
        val record = update.record
        val view =
            FindingView(
                findingId = record.findingId,
                findingClass = record.findingClass,
                scenarioStep = record.scenarioStep,
                agentId = record.agentId,
                a = record.a?.let { TimelineTexts.line(it, TimelineTexts.SOURCE_CHARS) },
                b = record.b?.let { TimelineTexts.line(it, TimelineTexts.SOURCE_CHARS) },
                c = record.c?.let { TimelineTexts.line(it, TimelineTexts.SOURCE_CHARS) },
                note = TimelineTexts.line(record.note, TimelineTexts.SOURCE_CHARS),
                artifactIds = record.artifactIds,
            )
        return copy(findings = (listOf(view) + findings).take(FINDINGS_LIMIT), tally = tally.copy(findings = tally.findings + 1))
            .log(
                at = update.at.wall,
                agentId = record.agentId,
                kind = TimelineKind.FINDING,
                status = if (record.findingClass in NOTICE_FINDINGS) TimelineStatus.INFO else TimelineStatus.FAIL,
                text = TimelineTexts.finding(record),
                scenarioStep = record.scenarioStep,
            )
    }

    // --- orchestrator ---------------------------------------------------------------------------------------------

    private fun onPlanReady(update: PlanReady): DashboardState {
        val runId = update.plan.runId
        val scoped = if (runId == null) this else claimed(runId, update.at)
        return scoped.copy(plan = update.plan)
    }

    private fun onTask(update: TaskUpdated): DashboardState? {
        val task = update.task.copy(updatedAt = update.task.updatedAt ?: update.at.wall)
        val step = tasks[task.stepId].orEmpty()
        if (step[task.agentId] == task) return null
        return copy(tasks = tasks + (task.stepId to step + (task.agentId to task)))
    }

    // --- building blocks ------------------------------------------------------------------------------------------

    private fun withRun(change: (RunTrack) -> RunTrack): DashboardState = run?.let { copy(run = change(it)) } ?: this

    private fun withCard(
        agentId: AgentId,
        change: (AgentCard) -> AgentCard,
    ): DashboardState {
        val track = agents[agentId] ?: AgentTrack.unknown(agentId)
        return copy(agents = agents + (agentId to track.copy(card = change(track.card))))
    }

    private fun log(
        at: Instant,
        agentId: AgentId?,
        kind: TimelineKind,
        status: TimelineStatus,
        text: String,
        scenarioStep: String? = null,
    ): DashboardState {
        val entry = TimelineEntry(nextSeq, at, agentId, kind, status, text, scenarioStep)
        val own =
            if (agentId == null) {
                agentTimelines
            } else {
                agentTimelines + (agentId to (listOf(entry) + agentTimelines[agentId].orEmpty()).take(AGENT_TIMELINE_LIMIT))
            }
        return copy(timeline = (listOf(entry) + timeline).take(TIMELINE_LIMIT), agentTimelines = own, nextSeq = nextSeq + 1)
    }

    private fun runView(now: HarnessTimestamp): RunView {
        val current = run ?: return RunView(null, null, null, null, 0, null, RunPhase.WAITING, null, null)
        val elapsed = current.durationMs ?: ((now.monotonicNanos - current.startedNanos) / NANOS_PER_MILLI).coerceAtLeast(0)
        val phase =
            when {
                !current.ended -> RunPhase.RUNNING
                current.outcome == null -> RunPhase.INTERRUPTED
                else -> RunPhase.FINISHED
            }
        return RunView(
            runId = current.runId,
            campaignName = current.campaignName,
            target = current.target,
            startedAt = current.startedAt,
            elapsedMs = elapsed,
            currentScenarioStep = current.currentScenarioStep,
            phase = phase,
            outcome = current.outcome,
            reportPath = current.reportPath,
        )
    }

    private fun counters(cards: List<AgentCard>): DashboardCounters {
        val byState = cards.groupingBy { it.state }.eachCount()
        return DashboardCounters(
            agentsByState = AgentState.entries.associateWith { byState[it] ?: 0 },
            stepsPassed = tally.stepsPassed,
            stepsFailed = tally.stepsFailed,
            assertionsPassed = tally.assertionsPassed,
            assertionsFailed = tally.assertionsFailed,
            assertionsSkipped = tally.assertionsSkipped,
            events = tally.events,
            receiptsReceived = tally.receiptsReceived,
            receiptsMissing = tally.receiptsMissing,
            findings = tally.findings,
        )
    }

    private fun copy(
        version: Long = this.version,
        run: RunTrack? = this.run,
        agents: Map<AgentId, AgentTrack> = this.agents,
        timeline: List<TimelineEntry> = this.timeline,
        agentTimelines: Map<AgentId, List<TimelineEntry>> = this.agentTimelines,
        findings: List<FindingView> = this.findings,
        tally: Tally = this.tally,
        eventNames: Map<EventId, String> = this.eventNames,
        nextSeq: Long = this.nextSeq,
        orchestratorVersion: Long = this.orchestratorVersion,
        plan: RunPlanView? = this.plan,
        tasks: Map<String, Map<AgentId, TaskStateView>> = this.tasks,
        events: List<EventView> = this.events,
    ) = DashboardState(
        version,
        run,
        agents,
        timeline,
        agentTimelines,
        findings,
        tally,
        eventNames,
        nextSeq,
        orchestratorVersion,
        plan,
        tasks,
        events,
    )

    private data class RunTrack(
        val runId: RunId,
        val startedAt: Instant,
        /** Monotonic start, for the live elapsed time (CLAUDE.md rule 1). */
        val startedNanos: Long,
        val campaignName: String? = null,
        val target: String? = null,
        val currentScenarioStep: String? = null,
        val ended: Boolean = false,
        val outcome: RunOutcome? = null,
        val durationMs: Long? = null,
        val reportPath: String? = null,
    ) {
        companion object {
            fun started(
                runId: RunId,
                at: HarnessTimestamp,
            ) = RunTrack(runId, at.wall, at.monotonicNanos)
        }
    }

    /** A card plus the last action text the orchestrator reported for it (see "Last action" above). */
    private data class AgentTrack(
        val card: AgentCard,
        val boardAction: String?,
    ) {
        companion object {
            fun unknown(agentId: AgentId) =
                AgentTrack(
                    AgentCard(
                        agentId = agentId,
                        displayName = agentId.value,
                        role = null,
                        department = null,
                        registration = null,
                        state = AgentState.IDLE,
                        scenarioStep = null,
                        lastAction = null,
                        lastActionAt = null,
                        lastScreenshotArtifactId = null,
                        actionsDone = 0,
                        failures = 0,
                        lastFailureReason = null,
                    ),
                    boardAction = null,
                )
        }
    }

    private data class Tally(
        val stepsPassed: Int = 0,
        val stepsFailed: Int = 0,
        val assertionsPassed: Int = 0,
        val assertionsFailed: Int = 0,
        val assertionsSkipped: Int = 0,
        val events: Int = 0,
        val receiptsReceived: Int = 0,
        val receiptsMissing: Int = 0,
        val findings: Int = 0,
    )

    companion object {
        const val TIMELINE_LIMIT = 300
        const val AGENT_TIMELINE_LIMIT = 30
        const val FINDINGS_LIMIT = 200
        const val EVENTS_LIMIT = 200

        /** Nothing seen yet. */
        val EMPTY: DashboardState =
            DashboardState(
                0,
                null,
                emptyMap(),
                emptyList(),
                emptyMap(),
                emptyList(),
                Tally(),
                emptyMap(),
                1,
                0,
                null,
                emptyMap(),
                emptyList(),
            )

        private const val NANOS_PER_MILLI = 1_000_000
        private val FAILED_STEPS = setOf(StepStatus.FAILED, StepStatus.ERROR, StepStatus.BLOCKED)

        /** Findings that ask for a closer look rather than report a defect. */
        private val NOTICE_FINDINGS = setOf(FindingClass.INVESTIGATE, FindingClass.FLAKY)
        private val AGENT_TOKEN = Regex("^a\\d+\\b")

        /** The agent an artifact belongs to: the first path segment that is an agent id (`<run>/<agent>/<file>`). */
        private fun ownerOf(record: ArtifactRecord): AgentId? = record.relativePath.split('/', '\\').firstNotNullOfOrNull(::agentIdOrNull)

        /** Harness messages start with the agent they are about (`a07 failed setup step ...`). */
        private fun agentMentionedIn(text: String): AgentId? = AGENT_TOKEN.find(text.trim())?.value?.let(::agentIdOrNull)

        private fun agentIdOrNull(text: String): AgentId? =
            try {
                AgentId(text)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
