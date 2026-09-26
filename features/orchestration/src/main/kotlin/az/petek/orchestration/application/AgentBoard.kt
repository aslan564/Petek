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

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.time.HarnessClock
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Keeps the latest [AgentStatus] per agent and forwards changes to the [MonitorView]. A misbehaving monitor is only
 * logged: observing the run must never break it.
 */
internal class AgentBoard(
    private val monitor: MonitorView,
    private val clock: HarnessClock,
) {
    private val statuses = ConcurrentHashMap<AgentId, AgentStatus>()

    fun start(
        runId: RunId,
        identities: List<Identity>,
    ) {
        val now = clock.now()
        identities.forEach {
            statuses[it.agentId] = AgentStatus(it.agentId, it.displayName, it.role.key, AgentState.IDLE, null, null, now)
        }
        safely { monitor.runStarted(runId, identities.mapNotNull { statuses[it.agentId] }) }
    }

    fun update(
        agentId: AgentId,
        state: AgentState,
        scenarioStep: String?,
        lastAction: String?,
    ) {
        val updated =
            statuses.computeIfPresent(agentId) { _, old ->
                old.copy(
                    state = state,
                    scenarioStep = scenarioStep ?: old.scenarioStep,
                    lastAction = lastAction?.let(::shorten) ?: old.lastAction,
                    updatedAt = clock.now(),
                )
            } ?: return
        safely { monitor.agentUpdated(updated) }
    }

    fun stepStarted(scenarioStep: String) = safely { monitor.stepStarted(scenarioStep) }

    fun message(text: String) = safely { monitor.message(text) }

    fun finished(summary: RunSummary) = safely { monitor.runFinished(summary) }

    private fun shorten(text: String): String {
        val line =
            text
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
        return if (line.length <= MAX_ACTION_CHARS) line else line.take(MAX_ACTION_CHARS - 1) + "…"
    }

    private inline fun safely(call: () -> Unit) {
        try {
            call()
        } catch (e: Exception) {
            logger.warn(e) { "monitor view failed; the run continues" }
        }
    }

    private companion object {
        const val MAX_ACTION_CHARS = 80
    }
}
