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
import az.petek.agent.domain.SharedRunState
import az.petek.browser.domain.BrowserProxy
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.TextWatch
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.ScenarioStep
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.core.time.HarnessTimestamp
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Mutable state of one run, shared by the runner and the step executor. Safe for concurrent agents. */
internal class RunState(
    val runId: RunId,
    val campaign: Campaign,
    val options: RunOptions,
    val startedAt: HarnessTimestamp,
    bus: EventBus,
    val shared: SharedRunState,
) {
    /** Where events are published and awaited; each wave gets its own, so live events stay within it (Faza 21). */
    @Volatile
    var bus: EventBus = bus
        set(value) {
            field = value
            // A new bus counts its sequences from the start: where the old one stood says nothing about it.
            stepStarts.clear()
            emptySteps.clear()
            watches.clear()
        }

    /** The testers of the wave now running; null when everyone runs at once. */
    @Volatile
    var wave: Set<AgentId>? = null

    /**
     * Which execution of the steps runs now (`{pass}`): 1 in the first pass, the wave's number in later waves, the next
     * number in the account swap.
     */
    @Volatile
    var pass: Int = 1

    private val tag: String = RunTags.forRun(runId).value

    /** `{pass}` of execution [pass]: `<run tag>-<n>`, so a text marked with it is new in every execution and every run. */
    fun passMark(pass: Int = this.pass): String = "$tag-$pass"

    val budget: Duration = campaign.settings.budget.maxMinutes.minutes

    /** The events setup steps published, in order: every wave's bus starts with them (Faza 24.11). */
    val setupEvents: MutableList<PublishedEvent> = CopyOnWriteArrayList()

    /** Every event name some step emits; `{event.<name>.id}` templates are resolved from these. */
    val eventNames: List<String> = campaign.allSteps.mapNotNull { it.emits?.event }.distinct()

    /** The step that emits each event (the validator allows exactly one per event). */
    private val emitters: Map<String, String> =
        campaign.allSteps.mapNotNull { step -> step.emits?.let { it.event to step.id } }.toMap()

    /**
     * Where the bus stood when the latest execution of each step began, by step id without the swap suffix. Every
     * event that execution emits comes later, so an event is taken only from the latest execution of its step: the
     * account swap runs the main steps again on the same bus, and a waiter there must never take the first pass's
     * event (Faza 24.4).
     */
    private val stepStarts = ConcurrentHashMap<String, Long>()

    /** Remembers where the bus stands as [stepId] begins; see [eventCursor]. */
    fun stepStarted(stepId: String) {
        val base = stepId.removeSuffix(DefaultCampaignRunner.SWAP_SUFFIX)
        stepStarts[base] = bus.latestAny()?.sequence ?: 0L
        emptySteps -= base
    }

    /** Steps that had no tester to run them on this bus (a wave without their testers), by id without the swap suffix. */
    private val emptySteps: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [stepId] had no tester here: nothing it emits can arrive on this bus. */
    fun stepHadNoTester(stepId: String) {
        emptySteps += stepId.removeSuffix(DefaultCampaignRunner.SWAP_SUFFIX)
    }

    /** The step that emits [event] when it had no tester on this bus (Faza 24.7); null when it ran or never started. */
    fun absentEmitter(event: String): String? = emitters[event]?.takeIf { it in emptySteps }

    /**
     * A receiver's watch for the text of its reception check, started before the change it waits for was written (Faza
     * 24.10): the page's watch [key], the [text] as rendered then and what the page answered ([TextWatch.NotYet] or
     * [TextWatch.WasThere]).
     */
    class ArmedWatch(
        val key: String,
        val text: String,
        val reading: TextWatch,
    )

    /** Watches not read yet, by the executed step id of the receiving step (`read`, `read@swap`) and the receiver. */
    private val watches = ConcurrentHashMap<Pair<String, AgentId>, ArmedWatch>()

    fun armed(
        stepId: String,
        agentId: AgentId,
        watch: ArmedWatch,
    ) {
        watches[stepId to agentId] = watch
    }

    /** Takes the watch [agentId] started for [stepId], if any; each watch is taken once. */
    fun takeWatch(
        stepId: String,
        agentId: AgentId,
    ): ArmedWatch? = watches.remove(stepId to agentId)

    /** The receivers with a watch for [stepId] not taken yet. */
    fun watchersOf(stepId: String): List<AgentId> = watches.keys.filter { it.first == stepId }.map { it.second }

    /** How the receivers of one `wait_for` step fared: waited for the event, or had no emitter in their wave. */
    class ReceiverCoverage {
        val waited = AtomicInteger()
        val withoutEmitter = AtomicInteger()
    }

    /** Per `wait_for` step as it ran (`read`, `read@swap`), in the order the steps first had receivers. */
    val receivers: MutableMap<String, ReceiverCoverage> = java.util.Collections.synchronizedMap(LinkedHashMap())

    fun receiversOf(stepId: String): ReceiverCoverage = synchronized(receivers) { receivers.getOrPut(stepId) { ReceiverCoverage() } }

    /**
     * The sequence an [event] must come after to belong to the latest execution of the step that emits it; 0 when that
     * step has not run on this bus.
     */
    fun eventCursor(event: String): Long = emitters[event]?.let { stepStarts[it] } ?: 0L

    @Volatile
    var identities: List<Identity> = emptyList()

    @Volatile
    var browserStarted: Boolean = false

    /** The browser's session factory, started once for the run (every wave opens its sessions from it). */
    @Volatile
    var factory: BrowserSessionFactory? = null

    val sessions = ConcurrentHashMap<AgentId, BrowserSession>()
    val agents = ConcurrentHashMap<AgentId, TesterAgent>()

    /** The proxy each account's browser went out through (Faza 21); a swapped account keeps its own. */
    val proxies = ConcurrentHashMap<AgentId, BrowserProxy>()
    val tally = StepTally()

    /** Ids of the scenario steps that have been started, in order. */
    val startedSteps: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /** The agents each started step was run by (step id -> agent ids), as resolved when it started. */
    val executedActors = ConcurrentHashMap<String, List<AgentId>>()

    /**
     * Every execution of steps the run planned, in order (the roll call at the run's end reads it): the run without
     * waves, or each wave, registered before anything runs, and the account swap when it begins.
     */
    val passes: MutableList<PlannedPass> = CopyOnWriteArrayList()

    private val begunPasses: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** The passes that began, by [PlannedPass.number]; a wave that never began is told apart in the roll call. */
    fun passBegan(number: Int) {
        begunPasses += number
    }

    fun hasBegun(number: Int): Boolean = number in begunPasses

    /** Who each step started with, per pass: (pass, executed step id) -> the actors chosen then. */
    private val chosen = ConcurrentHashMap<Pair<Int, String>, List<AgentId>>()

    /** Who was out when each step started, per pass, with the same key as [chosen]. */
    private val outAtStart = ConcurrentHashMap<Pair<Int, String>, List<AgentId>>()

    /**
     * [stepId] started in the pass now running with [actors] (none when nobody matched), while [out] were out after
     * failing earlier: the testers of its role, department and registration, whatever place `n` names, since a tester
     * out before it shifts who the n-th one is.
     */
    fun stepChose(
        stepId: String,
        actors: List<AgentId>,
        out: List<AgentId> = emptyList(),
    ) {
        chosen[pass to stepId] = actors
        outAtStart[pass to stepId] = out
    }

    /** The testers out when any execution of the step [baseId] (swap suffix removed) started, in agent order. */
    fun outOf(baseId: String): List<AgentId> =
        outAtStart.entries
            .filter { it.key.second.removeSuffix(DefaultCampaignRunner.SWAP_SUFFIX) == baseId }
            .flatMap { it.value }
            .distinct()
            .sorted()

    /** The actors [stepId] started with in pass [number]; null when it never started there. */
    fun chosenIn(
        number: Int,
        stepId: String,
    ): List<AgentId>? = chosen[number to stepId]

    /** The actors of every execution of the step [baseId] (swap suffix removed); empty when it never started. */
    fun executionsOf(baseId: String): List<List<AgentId>> =
        chosen.entries.filter { it.key.second.removeSuffix(DefaultCampaignRunner.SWAP_SUFFIX) == baseId }.map { it.value }

    private val settled: MutableSet<Triple<Int, String, AgentId>> = ConcurrentHashMap.newKeySet()

    /** [agentId] has the final record of its part in [stepId] in the pass now running (its action, or why it had none). */
    fun settle(
        stepId: String,
        agentId: AgentId,
    ) {
        settled += Triple(pass, stepId, agentId)
    }

    fun isSettled(
        number: Int,
        stepId: String,
        agentId: AgentId,
    ): Boolean = Triple(number, stepId, agentId) in settled

    /** Company ids already registered as run resources. */
    val companies: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val statuses = ConcurrentHashMap<AgentId, IdentityStatus>()
    private val failureReasons = ConcurrentHashMap<AgentId, String>()
    private val abortReason = AtomicReference<String?>(null)

    val aborted: Boolean get() = abortReason.get() != null

    val abortedBecause: String? get() = abortReason.get()

    /** Marks the run ABORTED; the first reason wins. Returns true if this call aborted the run. */
    fun abort(reason: String): Boolean = abortReason.compareAndSet(null, reason)

    fun status(agentId: AgentId): IdentityStatus = statuses[agentId] ?: IdentityStatus.PLANNED

    fun setStatus(
        agentId: AgentId,
        status: IdentityStatus,
    ) {
        statuses[agentId] = status
    }

    /** Excludes the agent from every later step; the first reason is kept. */
    fun markFailed(
        agentId: AgentId,
        reason: String,
    ) {
        statuses[agentId] = IdentityStatus.FAILED
        failureReasons.putIfAbsent(agentId, reason)
    }

    fun failureReason(agentId: AgentId): String? = failureReasons[agentId]

    fun isFailed(agentId: AgentId): Boolean = status(agentId) == IdentityStatus.FAILED

    /** The testers of the current wave (everyone without waves). */
    fun waveIdentities(): List<Identity> = wave?.let { members -> identities.filter { it.agentId in members } } ?: identities

    fun activeIdentities(): List<Identity> = waveIdentities().filterNot { isFailed(it.agentId) }

    /** ABORTED, else FAILED when anything failed or a check could not be decided (nothing proves it), else PASSED. */
    fun outcome(): RunOutcome =
        when {
            aborted -> RunOutcome.ABORTED
            tally.anyFailure || tally.assertionsInconclusive > 0 -> RunOutcome.FAILED
            else -> RunOutcome.PASSED
        }
}

/**
 * One execution of steps the run planned: the whole run ([wave] null), one wave of [waves], or the account swap
 * ([label] `swap`). [number] is the execution's `{pass}` ([RunState.pass] while it runs).
 */
internal class PlannedPass(
    val number: Int,
    val steps: List<PassStep>,
    /** 1-based, when the run has waves. */
    val wave: Int? = null,
    val waves: Int? = null,
    val label: String? = wave?.let { "wave $it" },
)

/** A step of a [PlannedPass] and the testers it resolves its actors against there ([RunState.wave] while it runs). */
internal class PassStep(
    val step: ScenarioStep,
    val pool: List<Identity>,
)

/** How a recorded step counts in the run summary. */
internal enum class Tally { PASS, FAIL, NONE }

/** Counters for [az.petek.orchestration.domain.RunSummary]; only scenario work is counted, not harness housekeeping. */
internal class StepTally {
    private val passed = AtomicInteger()
    private val failed = AtomicInteger()
    private val assertionFailures = AtomicInteger()
    private val undecided = AtomicInteger()
    private val agentsWithFailures: MutableSet<AgentId> = ConcurrentHashMap.newKeySet()

    val stepsPassed: Int get() = passed.get()
    val stepsFailed: Int get() = failed.get()
    val assertionsFailed: Int get() = assertionFailures.get()

    /** Checks whose evidence could not decide them (Faza 24.12): no failure of the agent, but no proof either. */
    val assertionsInconclusive: Int get() = undecided.get()
    val failedAgents: Int get() = agentsWithFailures.size

    /** Whether [agentId] failed a step or one of its assertions so far. */
    fun hasFailures(agentId: AgentId): Boolean = agentId in agentsWithFailures

    val anyFailure: Boolean get() = stepsFailed > 0 || assertionsFailed > 0

    fun step(
        tally: Tally,
        agentId: AgentId?,
    ) {
        if (tally == Tally.PASS) passed.incrementAndGet()
        if (tally == Tally.FAIL) {
            failed.incrementAndGet()
            agentId?.let(agentsWithFailures::add)
        }
    }

    fun assertionFailed(agentId: AgentId?) {
        assertionFailures.incrementAndGet()
        agentId?.let(agentsWithFailures::add)
    }

    fun assertionInconclusive() {
        undecided.incrementAndGet()
    }
}
