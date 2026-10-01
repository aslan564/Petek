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
import az.petek.llm.domain.LlmCallObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * AI calls: the testers share a few slots for AI calls (`PETEK_LLM_CONCURRENCY`), so with many testers a call may wait
 * minutes for the others' calls. That wait is the run's size, not the tester being stuck, so it never counts: while the
 * action waits for a slot the guard's clock stands still, and it starts afresh when the slot comes. The call itself is
 * guarded like anything else (its start and its end are progress): an AI that does not answer within `timeout` blocks
 * the action with `llm_unavailable`, an environment problem, instead of `timeout`, which would blame the tester's agent.
 * The guard learns this through the [LlmCallObserver] it puts into the action's coroutine context.
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
     * progress (time spent waiting for an AI slot does not count, see the class KDoc). Once the watchdog has cancelled
     * the action the result is BLOCKED however the action ended — also when it turned the cancellation into an
     * exception of its own (e.g. a browser error). Otherwise exceptions thrown by [block] propagate unchanged. A
     * non-positive or infinite [timeout] disables the watchdog for this call.
     */
    suspend fun guard(
        agentId: AgentId,
        timeout: Duration,
        block: suspend () -> ActionOutcome,
    ): ActionOutcome {
        if (!timeout.isPositive() || timeout.isInfinite()) return block()
        val signal = counter(agentId)
        val calls = AiCalls(agentId)
        val blocked = AtomicReference<ActionOutcome?>(null)
        return try {
            coroutineScope {
                val work = async(calls) { block() }
                val watcher =
                    launch {
                        var seen = signal.value
                        while (true) {
                            val next =
                                withTimeoutOrNull(timeout) {
                                    combine(signal, calls.waiting) { progress, waiting -> progress to waiting }
                                        .first { (progress, waiting) -> progress != seen || waiting > 0 }
                                }
                            if (next == null) {
                                blocked.set(blockedOutcome(timeout, aiAnswering = calls.answering.get() > 0))
                                work.cancel(CancellationException("agent $agentId made no progress for $timeout"))
                                return@launch
                            }
                            // Waiting for an AI slot is never held against the tester: the clock restarts when it ends.
                            if (next.second > 0) calls.waiting.first { it == 0 }
                            seen = signal.value
                        }
                    }
                try {
                    work.await()
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: Exception) {
            val outcome = blocked.get() ?: throw e
            // The caller's own cancellation (budget, abort) still wins over the watchdog's verdict.
            currentCoroutineContext().ensureActive()
            outcome
        }
    }

    private fun counter(agentId: AgentId): MutableStateFlow<Long> = counters.computeIfAbsent(agentId) { MutableStateFlow(0L) }

    /** How the AI calls of one guarded action stand: how many wait for a slot, how many are at the provider. */
    private inner class AiCalls(
        private val agentId: AgentId,
    ) : LlmCallObserver() {
        val waiting = MutableStateFlow(0)
        val answering = AtomicInteger()

        override fun slotWaitStarted() {
            waiting.update { it + 1 }
        }

        override fun slotWaitEnded() {
            waiting.update { it - 1 }
        }

        override fun callStarted() {
            answering.incrementAndGet()
            progress(agentId)
        }

        override fun callEnded() {
            answering.decrementAndGet()
            progress(agentId)
        }
    }

    private fun blockedOutcome(
        timeout: Duration,
        aiAnswering: Boolean,
    ) = if (aiAnswering) {
        ActionOutcome(
            status = ActionStatus.BLOCKED,
            summary = "agent blocked: the AI did not answer within $timeout, action cancelled",
            failureReason = FailureReason.LLM_UNAVAILABLE,
        )
    } else {
        ActionOutcome(
            status = ActionStatus.BLOCKED,
            summary = "agent blocked (no progress for $timeout), action cancelled",
            failureReason = FailureReason.TIMEOUT,
        )
    }
}
