package az.petek.explorer.application

import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationObserver
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An [ExplorationObserver] that publishes events as a hot flow for live views (web panel, CLI). It never blocks the
 * explorer: when collectors fall behind by more than [buffer] events the oldest are dropped, and a late subscriber
 * gets the last [replay] events. A view that must not miss anything replays the stored log
 * ([az.petek.explorer.domain.ExplorationEventLog.events]) and continues from the flow by sequence number.
 */
class FlowExplorationObserver(
    replay: Int = 64,
    buffer: Int = 1_024,
) : ExplorationObserver {
    private val flow =
        MutableSharedFlow<ExplorationEvent>(replay = replay, extraBufferCapacity = buffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<ExplorationEvent> = flow.asSharedFlow()

    override fun onEvent(event: ExplorationEvent) {
        flow.tryEmit(event)
    }
}
