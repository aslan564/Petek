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

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.core.ids.AgentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Detects agents that stopped making progress (docs/ARCHITECTURE.md "Watchdog") so one stuck agent never holds up
 * all the others. Progress is reported through [progress], normally by [ProgressTrackingRecorder] whenever the agent
 * records evidence.
 *
 * [guard] runs an action and cancels it once its agent has made no progress for `timeout`, returning a
 * [ActionStatus.BLOCKED] outcome instead. Cancellation is cooperative and structured: the guard returns only after
 * the cancelled action has actually stopped, so a blocked action can never keep driving the agent's browser session
 * while the agent already works on its next step. Browser calls are bounded by their own timeouts, which bounds that
 * wait as well. Timing uses coroutine time, so tests control it with virtual time.
 *
 * One instance may serve many runs: each [guard] call takes its own baseline, so progress from an earlier action or
 * run never extends a later one.
 */
class InactivityWatchdog {
    private val counters = ConcurrentHashMap<AgentId, MutableStateFlow<Long>>()

    /** Records a sign of life for [agentId]. Cheap and non-blocking; safe to call from any thread. */
    fun progress(agentId: AgentId) {
        counter(agentId).update { it + 1 }
    }

    /** Number of progress signals received for [agentId] so far (for diagnostics and tests). */
    fun progressCount(agentId: AgentId): Long = counters[agentId]?.value ?: 0L

    /**
     * Runs [block] for [agentId]; returns its outcome, or a BLOCKED outcome if the agent went [timeout] without
     * progress. Once the watchdog has cancelled the action the result is BLOCKED however the action ended — also
     * when it turned the cancellation into an exception of its own (e.g. a browser error). Otherwise exceptions thrown
     * by [block] propagate unchanged. A non-positive or infinite [timeout] disables the watchdog for this call.
     */
    suspend fun guard(
        agentId: AgentId,
        timeout: Duration,
        block: suspend () -> ActionOutcome,
    ): ActionOutcome {
        if (!timeout.isPositive() || timeout.isInfinite()) return block()
        val signal = counter(agentId)
        val blocked = AtomicBoolean(false)
        return try {
            coroutineScope {
                val work = async { block() }
                val watcher =
                    launch {
                        var seen = signal.value
                        while (true) {
                            val next = withTimeoutOrNull(timeout) { signal.first { it != seen } }
                            if (next == null) {
                                blocked.set(true)
                                work.cancel(CancellationException("agent $agentId made no progress for $timeout"))
                                return@launch
                            }
                            seen = next
                        }
                    }
                try {
                    work.await()
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: Exception) {
            if (!blocked.get()) throw e
            // The caller's own cancellation (budget, abort) still wins over the watchdog's verdict.
            currentCoroutineContext().ensureActive()
            blockedOutcome(timeout)
        }
    }

    private fun counter(agentId: AgentId): MutableStateFlow<Long> = counters.computeIfAbsent(agentId) { MutableStateFlow(0L) }

    private fun blockedOutcome(timeout: Duration) =
        ActionOutcome(
            status = ActionStatus.BLOCKED,
            summary = "agent blocked (no progress for $timeout), action cancelled",
            failureReason = FailureReason.TIMEOUT,
        )
}
