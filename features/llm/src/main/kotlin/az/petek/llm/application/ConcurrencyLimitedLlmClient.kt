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

package az.petek.llm.application

import az.petek.llm.domain.LlmCallObserver
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore

/**
 * Lets at most [permits] calls reach [delegate] at once; the others suspend (they do not fail) until a slot frees up.
 * Thirty agents share one plan or API key, and the provider rate-limits bursts harder than a steady queue.
 * Waiting callers stay cancellable, and a cancelled or failed call always returns its permit.
 *
 * Put it inside [RetryingLlmClient] (`RetryingLlmClient(ConcurrencyLimitedLlmClient(provider, n))`): a permit then
 * covers one attempt, so a call sleeping through its backoff does not keep a slot from the other agents.
 *
 * The [LlmCallObserver] in the caller's coroutine context, if any, hears how each call goes: the wait for a slot (only
 * when the call has to wait) and the call itself. With many testers on a few slots a call may wait minutes for the
 * others; the runner's watchdog uses this to never count that wait against the tester whose call it is.
 */
class ConcurrencyLimitedLlmClient(
    private val delegate: LlmClient,
    permits: Int,
) : LlmClient by delegate {
    init {
        require(permits >= 1) { "permits must be at least 1, was $permits" }
    }

    private val semaphore = Semaphore(permits)

    /** Free slots right now; for monitoring. */
    val availablePermits: Int get() = semaphore.availablePermits

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val observer = currentCoroutineContext()[LlmCallObserver]
        if (!semaphore.tryAcquire()) {
            observer?.slotWaitStarted()
            try {
                semaphore.acquire()
            } finally {
                observer?.slotWaitEnded()
            }
        }
        try {
            observer?.callStarted()
            try {
                return delegate.complete(request)
            } finally {
                observer?.callEnded()
            }
        } finally {
            semaphore.release()
        }
    }
}
