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

package az.petek.faketarget.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Rendezvous used by [az.petek.faketarget.FakeBug.RACE_DOUBLE_APPROVE] to make a check-then-act race reproducible:
 * the first caller for a key waits up to [window] for a partner; the partner releases it and both continue at once.
 * With two concurrent decisions both have passed their check before either writes, so both succeed.
 */
internal class RaceWindow(
    private val window: Duration,
) {
    private val lock = Any()
    private val waiting = HashMap<String, CompletableDeferred<Unit>>()

    suspend fun await(key: String) {
        val (gate, first) =
            synchronized(lock) {
                val existing = waiting.remove(key)
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<Unit>().also { waiting[key] = it } to true
                }
            }
        if (!first) {
            gate.complete(Unit)
            return
        }
        try {
            withTimeoutOrNull(window) { gate.await() }
        } finally {
            // Also when the caller is cancelled (client gone), so a stale gate never releases a later caller early.
            synchronized(lock) { if (waiting[key] === gate) waiting.remove(key) }
        }
    }
}
