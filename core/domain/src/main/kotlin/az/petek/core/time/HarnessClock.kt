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

package az.petek.core.time

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A point in time as measured by the harness (AGENTS.md rule 1: time is never measured by the LLM).
 * [wall] is for records and reports; [monotonicNanos] is for latency maths inside one JVM.
 */
data class HarnessTimestamp(
    val wall: Instant,
    val monotonicNanos: Long,
) {
    fun elapsedUntil(later: HarnessTimestamp): Duration = (later.monotonicNanos - monotonicNanos).nanoseconds
}

interface HarnessClock {
    fun now(): HarnessTimestamp
}

class SystemHarnessClock : HarnessClock {
    override fun now(): HarnessTimestamp = HarnessTimestamp(Instant.now(), System.nanoTime())
}
