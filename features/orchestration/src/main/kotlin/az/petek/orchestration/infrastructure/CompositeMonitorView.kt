/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunPlan
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.domain.TaskUpdate
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Fans every notification out to several views (e.g. the live board, the web panel and the log file), in list order.
 * A view that throws is logged and skipped so the others still see the call and the run is never affected.
 */
class CompositeMonitorView(
    private val views: List<MonitorView>,
) : MonitorView {
    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) = each { it.runStarted(runId, agents) }

    override fun agentUpdated(status: AgentStatus) = each { it.agentUpdated(status) }

    override fun stepStarted(scenarioStep: String) = each { it.stepStarted(scenarioStep) }

    override fun message(text: String) = each { it.message(text) }

    override fun runFinished(summary: RunSummary) = each { it.runFinished(summary) }

    override fun planReady(plan: RunPlan) = each { it.planReady(plan) }

    override fun taskUpdated(update: TaskUpdate) = each { it.taskUpdated(update) }

    override fun eventPublished(event: PublishedEvent) = each { it.eventPublished(event) }

    override fun eventReceived(
        eventName: String,
        agentId: AgentId,
        latencyMs: Long?,
        received: Boolean,
    ) = each { it.eventReceived(eventName, agentId, latencyMs, received) }

    private inline fun each(call: (MonitorView) -> Unit) {
        views.forEach { view ->
            try {
                call(view)
            } catch (e: Exception) {
                logger.warn(e) { "monitor view ${view::class.simpleName} failed" }
            }
        }
    }
}
