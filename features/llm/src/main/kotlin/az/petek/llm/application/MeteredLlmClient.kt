package az.petek.llm.application

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import kotlin.coroutines.cancellation.CancellationException

/**
 * Records every call that reaches it in [meter]: successes with their usage, failures as failed calls.
 * Placed outermost (around retries), it counts one call per agent decision rather than one per HTTP attempt.
 * Cancelled calls are not counted: they are neither an answer nor a provider failure.
 */
class MeteredLlmClient(
    private val delegate: LlmClient,
    private val meter: UsageMeter,
) : LlmClient by delegate {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val response =
            try {
                delegate.complete(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                meter.recordFailure(request.label)
                throw failure
            }
        meter.record(request.label, response)
        return response
    }
}
