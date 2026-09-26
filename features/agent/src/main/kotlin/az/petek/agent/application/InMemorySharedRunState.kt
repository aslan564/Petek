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

package az.petek.agent.application

import az.petek.agent.domain.SharedRunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * [SharedRunState] for one run inside one process. Writers replace the whole map atomically, and waiters suspend on
 * the state flow until their key appears (no polling), so any number of agents waiting for `company_code` cost nothing
 * until the admin publishes it. Keys are write-once: a later [put] of a different value is refused and the first value
 * stays (the others may already be acting on it).
 */
class InMemorySharedRunState : SharedRunState {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun get(key: String): String? = values.value[key]

    override fun put(
        key: String,
        value: String,
    ): Boolean = values.updateAndGet { if (key in it) it else it + (key to value) }[key] == value

    override suspend fun await(
        key: String,
        timeout: Duration,
    ): String? = get(key) ?: withTimeoutOrNull(timeout) { values.mapNotNull { it[key] }.first() }

    /** Everything published so far (for diagnostics and reports). */
    fun snapshot(): Map<String, String> = values.value
}
