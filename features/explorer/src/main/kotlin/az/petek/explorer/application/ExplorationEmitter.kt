package az.petek.explorer.application

import az.petek.core.time.HarnessClock
import az.petek.explorer.domain.EventHeader
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationEventLog
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationObserver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/**
 * Numbers, stores and publishes the events of one exploration: store first (so a panel that replays the log never
 * misses what it was shown live), then notify the observer. Sequence numbers continue after [startAfter] without gaps,
 * also when several coroutines emit at once. A failing observer is logged and ignored.
 */
internal class ExplorationEmitter(
    private val explorationId: ExplorationId,
    private val log: ExplorationEventLog,
    private val observer: ExplorationObserver,
    private val clock: HarnessClock,
    startAfter: Long = 0,
) {
    private val lock = Mutex()
    private var seq = startAfter

    suspend fun emit(event: (EventHeader) -> ExplorationEvent): ExplorationEvent =
        lock
            .withLock {
                val built = event(EventHeader(explorationId, seq + 1, clock.now().wall))
                log.append(built)
                seq = built.header.seq
                built
            }.also(::notify)

    private fun notify(event: ExplorationEvent) {
        try {
            observer.onEvent(event)
        } catch (e: Exception) {
            logger.warn(e) { "Exploration observer failed on ${event::class.simpleName} of $explorationId; exploration continues" }
        }
    }
}
