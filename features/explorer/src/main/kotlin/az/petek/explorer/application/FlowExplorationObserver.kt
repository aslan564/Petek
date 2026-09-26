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
