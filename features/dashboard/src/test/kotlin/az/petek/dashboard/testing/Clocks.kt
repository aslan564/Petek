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
