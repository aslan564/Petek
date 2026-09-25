package az.petek.orchestration.application

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * One-shot start line for the actors of a `parallel: true` step (race tests need their actions to begin at the same
 * instant). Every party calls [arrive] exactly once — also an actor that drops out before acting, so the others are
 * never left waiting — and the barrier opens when the last one arrives.
 */
internal class StartBarrier(
    parties: Int,
) {
    private val remaining = AtomicInteger(parties)
    private val opened = CompletableDeferred<Unit>()

    init {
        require(parties >= 1) { "a barrier needs at least one party" }
    }

    fun arrive() {
        if (remaining.decrementAndGet() <= 0) opened.complete(Unit)
    }

    suspend fun awaitOpen() = opened.await()
}
