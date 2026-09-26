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
import az.petek.core.time.HarnessClock
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunPlan
import az.petek.orchestration.domain.TaskState
import az.petek.orchestration.domain.TaskUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * The orchestrator's task plan as the [MonitorView] sees it: the latest [RunPlan] and the state of every step × agent
 * task. It forwards only changes (a plan equal to the last one, or a task entering the state it is in, is not sent
 * again), so views get exactly one [TaskUpdate] per transition. Like [AgentBoard], a misbehaving monitor is only
 * logged: observing the run must never break it. Safe for concurrent agents; each task is updated by one coroutine.
 */
internal class TaskBoard(
    private val monitor: MonitorView,
    private val clock: HarnessClock,
) {
    private val states = ConcurrentHashMap<TaskKey, TaskState>()

    @Volatile
    private var plan: RunPlan? = null

    /**
     * Sends [next] when it differs from the last plan sent; every task it adds starts [TaskState.PENDING]. Called by
     * the runner only, between steps.
     */
    fun announce(next: RunPlan) {
        if (next == plan) return
        plan = next
        safely { monitor.planReady(next) }
        next.steps.forEach { step ->
            step.resolvedAgents.forEach { agent ->
                if (states.putIfAbsent(TaskKey(step.id, agent), TaskState.PENDING) == null) emit(step.id, agent, TaskState.PENDING, null)
            }
        }
    }

    /** Moves the task of [agentId] in [stepId] to [state]; nothing is sent when it already is in it. */
    fun update(
        stepId: String,
        agentId: AgentId,
        state: TaskState,
        detail: String?,
    ) {
        if (states.put(TaskKey(stepId, agentId), state) != state) emit(stepId, agentId, state, detail)
    }

    /**
     * Ends every task that has not reached a final state (the run stopped or never got to it) as SKIPPED with
     * [reason], in plan order.
     */
    fun closeOpen(reason: String) {
        val order = plan?.steps?.map { it.id }.orEmpty()

        fun position(key: TaskKey): Int = order.indexOf(key.stepId).takeIf { it >= 0 } ?: Int.MAX_VALUE
        states.entries
            .filterNot { it.value.isFinal }
            .map { it.key }
            .sortedWith(compareBy({ position(it) }, { it.agentId }))
            .forEach { update(it.stepId, it.agentId, TaskState.SKIPPED, reason) }
    }

    fun eventPublished(event: PublishedEvent) = safely { monitor.eventPublished(event) }

    fun eventReceived(
        eventName: String,
        agentId: AgentId,
        latencyMs: Long?,
        received: Boolean,
    ) = safely { monitor.eventReceived(eventName, agentId, latencyMs, received) }

    private fun emit(
        stepId: String,
        agentId: AgentId,
        state: TaskState,
        detail: String?,
    ) {
        val update = TaskUpdate(stepId, agentId, state, detail?.let(::shorten), clock.now())
        safely { monitor.taskUpdated(update) }
    }

    /** One line, bounded: a detail may carry an agent's whole summary. */
    private fun shorten(text: String): String {
        val line = text.replace(WHITESPACE, " ").trim()
        return if (line.length <= MAX_DETAIL_CHARS) line else line.take(MAX_DETAIL_CHARS - 1) + "…"
    }

    private inline fun safely(call: () -> Unit) {
        try {
            call()
        } catch (e: Exception) {
            logger.warn(e) { "monitor view failed; the run continues" }
        }
    }

    private data class TaskKey(
        val stepId: String,
        val agentId: AgentId,
    )

    private companion object {
        const val MAX_DETAIL_CHARS = 300
        val WHITESPACE = Regex("\\s+")
    }
}
