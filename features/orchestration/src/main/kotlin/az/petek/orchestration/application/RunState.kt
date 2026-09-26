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
import az.petek.agent.domain.SharedRunState
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.campaign.domain.Campaign
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.time.HarnessTimestamp
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import java.util.concurrent.ConcurrentHashMap
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

    /** The testers of the wave now running; null when everyone runs at once. */
    @Volatile
    var wave: Set<AgentId>? = null
    val budget: Duration = campaign.settings.budget.maxMinutes.minutes

    /** Every event name some step emits; `{event.<name>.id}` templates are resolved from these. */
    val eventNames: List<String> = campaign.allSteps.mapNotNull { it.emits?.event }.distinct()

    @Volatile
    var identities: List<Identity> = emptyList()

    @Volatile
    var browserStarted: Boolean = false

    /** The browser's session factory, started once for the run (every wave opens its sessions from it). */
    @Volatile
    var factory: BrowserSessionFactory? = null

    val sessions = ConcurrentHashMap<AgentId, BrowserSession>()
    val agents = ConcurrentHashMap<AgentId, TesterAgent>()
    val tally = StepTally()

    /** Ids of the scenario steps that have been started, in order. */
    val startedSteps: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /** The agents each started step was run by (step id -> agent ids), as resolved when it started. */
    val executedActors = ConcurrentHashMap<String, List<AgentId>>()

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

    fun outcome(): RunOutcome =
        when {
            aborted -> RunOutcome.ABORTED
            tally.anyFailure -> RunOutcome.FAILED
            else -> RunOutcome.PASSED
        }
}

/** How a recorded step counts in the run summary. */
internal enum class Tally { PASS, FAIL, NONE }

/** Counters for [az.petek.orchestration.domain.RunSummary]; only scenario work is counted, not harness housekeeping. */
internal class StepTally {
    private val passed = AtomicInteger()
    private val failed = AtomicInteger()
    private val assertionFailures = AtomicInteger()
    private val agentsWithFailures: MutableSet<AgentId> = ConcurrentHashMap.newKeySet()

    val stepsPassed: Int get() = passed.get()
    val stepsFailed: Int get() = failed.get()
    val assertionsFailed: Int get() = assertionFailures.get()
    val failedAgents: Int get() = agentsWithFailures.size
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
}
