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

import az.petek.campaign.domain.Pacing
import az.petek.core.ids.AgentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration

/**
 * Applies the campaign's [Pacing] to the actors of one step: the actor at position *n* in agent id order (from 0) may
 * start its action [Pacing.startStagger] × *n* after the step began, and at most [Pacing.maxParallelActors] act at
 * once. Waiting for the event of `wait_for` and the reception checks are not paced (they measure the target's delivery
 * time); only the action is. The start slots are timers in [scope] that begin with the step, so an actor that spent a
 * long time waiting for its event does not wait again; [close] cancels the timers of actors that never acted.
 */
internal class StartPacer(
    scope: CoroutineScope,
    pacing: Pacing,
    actors: List<AgentId>,
) {
    private val slots: Map<AgentId, Job> =
        if (pacing.startStagger <= Duration.ZERO) {
            emptyMap()
        } else {
            actors
                .sorted()
                .withIndex()
                .filter { (position, _) -> position > 0 }
                .associate { (position, agentId) -> agentId to scope.launch { delay(pacing.startStagger * position) } }
        }
    private val limit: Semaphore? = pacing.maxParallelActors?.let(::Semaphore)
    private val places: Int? = pacing.maxParallelActors

    /**
     * Runs [action] for [agentId] once its start slot has come and a place is free. [waiting] is told why the actor
     * waits, before it does (for the live board).
     */
    suspend fun <T> paced(
        agentId: AgentId,
        waiting: (String) -> Unit,
        action: suspend () -> T,
    ): T {
        slots[agentId]?.takeUnless { it.isCompleted }?.let { slot ->
            waiting("pacing: waiting for its start slot")
            slot.join()
        }
        val permits = limit ?: return action()
        if (permits.availablePermits == 0) waiting("pacing: waiting for one of $places places")
        return permits.withPermit { action() }
    }

    /** Stops the timers that are still running; the step is over. */
    fun close() {
        slots.values.forEach { it.cancel() }
    }
}
