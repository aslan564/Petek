/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.runs

import az.petek.app.panel.RunPlans
import az.petek.campaign.domain.Campaign
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.RunPlanView
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred

private val logger = KotlinLogging.logger {}

/**
 * Learns what the runner does with a run the panel started, through decorators of the container's repositories:
 * - the run's id as soon as its record is created ([wrap] of the [RunRepository]), so "Run et" answers with the id
 *   while the run goes on in the background;
 * - its plan as soon as its identities are planned ([wrap] of the [IdentityRepository]), which is before the first
 *   step: the plan (steps with the agents they resolve to) goes to [onPlan] for the orchestrator screen.
 *
 * [expect] arms the watch for the next run the panel starts; runs created while nothing is expected (another command
 * using the same repositories) are not watched. Thread-safe.
 */
internal class PanelRunWatch(
    private val onPlan: (RunPlanView) -> Unit,
) {
    private class Pending(
        val campaign: Campaign,
        val started: CompletableDeferred<RunId>,
    ) {
        @Volatile
        var runId: RunId? = null
    }

    private val lock = Any()
    private var pending: Pending? = null

    /** The next run created belongs to [campaign]; its id completes [started], its plan goes to the orchestrator. */
    fun expect(
        campaign: Campaign,
        started: CompletableDeferred<RunId>,
    ) = synchronized(lock) { pending = Pending(campaign, started) }

    fun wrap(delegate: RunRepository): RunRepository =
        object : RunRepository by delegate {
            override suspend fun create(run: RunRecord) {
                delegate.create(run)
                val waiting = synchronized(lock) { pending?.takeIf { it.runId == null }?.also { it.runId = run.runId } }
                waiting?.started?.complete(run.runId)
            }
        }

    fun wrap(delegate: IdentityRepository): IdentityRepository =
        object : IdentityRepository by delegate {
            override suspend fun replaceAll(
                runId: RunId,
                plan: IdentityPlan,
            ) {
                delegate.replaceAll(runId, plan)
                val planned =
                    synchronized(lock) {
                        pending?.takeIf { it.runId == runId }?.also { pending = null }
                    } ?: return
                // Observing a run must never break it.
                try {
                    onPlan(RunPlans.of(runId, planned.campaign, plan.identities))
                } catch (e: Exception) {
                    logger.warn(e) { "The plan of run $runId could not be shown" }
                }
            }
        }
}
