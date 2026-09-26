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
