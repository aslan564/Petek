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

import az.petek.core.time.HarnessClock
import az.petek.explorer.domain.EventHeader
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationEventLog
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationObserver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Numbers, stores and publishes the events of one exploration: store first (so a panel that replays the log never
 * misses what it was shown live), then notify the observer. Sequence numbers continue after [startAfter] without gaps,
 * also when several coroutines emit at once. A failing observer is logged and ignored.
 *
 * Storing is not cancellable: a store such as SQLite finishes its write on its own thread even when the caller is
 * cancelled (the exploration's time budget, a stop from the panel), so a cancellation that arrived during the write
 * would otherwise leave the counter behind the stored log and the closing event would reuse a stored number.
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
                withContext(NonCancellable) {
                    log.append(built)
                    seq = built.header.seq
                }
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
