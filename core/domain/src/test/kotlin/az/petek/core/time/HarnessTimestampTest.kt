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

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class HarnessTimestampTest {
    private val start = HarnessTimestamp(Instant.parse("2026-01-01T10:00:00Z"), 5_000_000)

    @Test
    fun `adding a duration moves the wall and the monotonic time together`() {
        val later = start + 1_250.microseconds

        later.wall shouldBe Instant.parse("2026-01-01T10:00:00.001250Z")
        later.monotonicNanos shouldBe 6_250_000
        start.elapsedUntil(later) shouldBe 1_250.microseconds
    }

    @Test
    fun `a negative duration goes back in time`() {
        val earlier = start + (-3).milliseconds

        earlier.wall shouldBe Instant.parse("2026-01-01T09:59:59.997Z")
        earlier.monotonicNanos shouldBe 2_000_000
    }
}
