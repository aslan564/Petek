/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.testing

import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** Manually advanced clock: wall time and monotonic time move together. */
class FakeHarnessClock(
    start: Instant = Instant.parse("2026-01-01T10:00:00Z"),
) : HarnessClock {
    private val nanos = AtomicLong(0)
    private val origin = start

    override fun now(): HarnessTimestamp {
        val n = nanos.get()
        return HarnessTimestamp(origin.plusNanos(n), n)
    }

    fun advance(by: Duration) {
        nanos.addAndGet(by.inWholeNanoseconds)
    }
}
