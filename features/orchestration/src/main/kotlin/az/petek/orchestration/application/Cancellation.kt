package az.petek.orchestration.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Rethrows when this coroutine itself is cancelled (budget, abort, the caller), so [error] can only be handled as a
 * failure when it is not our own cancellation. A [CancellationException] caught while this coroutine is still active
 * came from a callee — typically its own `withTimeout` — and must count as that callee's failure: rethrowing it would
 * silently end the step (or the whole run) as if someone had cancelled it.
 */
internal suspend fun rethrowIfCancelled(error: Exception) {
    if (error is CancellationException) currentCoroutineContext().ensureActive()
}
