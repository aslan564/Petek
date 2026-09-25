/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.testing

import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Instant

/** Harness time that follows the virtual time of a coroutine test, so throttling can be checked without sleeping. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerClock(
    private val scheduler: TestCoroutineScheduler,
    private val start: Instant = Records.T0,
) : HarnessClock {
    override fun now(): HarnessTimestamp {
        val millis = scheduler.currentTime
        return HarnessTimestamp(start.plusMillis(millis), millis * NANOS_PER_MILLI)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** A clock that can be told to fail, standing in for any error inside the dashboard. */
class FailingClock(
    private val delegate: HarnessClock,
) : HarnessClock {
    @Volatile
    var failing: Boolean = false

    override fun now(): HarnessTimestamp {
        check(!failing) { "clock broken on purpose" }
        return delegate.now()
    }
}
