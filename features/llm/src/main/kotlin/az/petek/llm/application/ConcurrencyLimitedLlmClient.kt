package az.petek.llm.application

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Lets at most [permits] calls reach [delegate] at once; the others suspend (they do not fail) until a slot frees up.
 * Thirty agents share one plan or API key, and the provider rate-limits bursts harder than a steady queue.
 * Waiting callers stay cancellable, and a cancelled or failed call always returns its permit.
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

    override suspend fun complete(request: LlmRequest): LlmResponse = semaphore.withPermit { delegate.complete(request) }
}
