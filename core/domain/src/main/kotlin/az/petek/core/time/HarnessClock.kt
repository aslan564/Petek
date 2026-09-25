/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.time

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A point in time as measured by the harness (CLAUDE.md rule 1: time is never measured by the LLM).
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
