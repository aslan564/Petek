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

package az.petek.capacity.application

import az.petek.capacity.application.RecommendCapacityUseCase.Measurement
import az.petek.capacity.domain.Bytes.GIB
import az.petek.capacity.domain.Bytes.MIB
import az.petek.capacity.domain.CapacityMeasurementException
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.HostResources
import az.petek.capacity.domain.SessionCost
import az.petek.capacity.domain.SessionCostProbe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI

class RecommendCapacityUseCaseTest {
    private val host = HostResources(totalMemoryBytes = 16 * GIB, availableMemoryBytes = 10 * GIB, cpuCores = 16)
    private val events = mutableListOf<String>()
    private val hostProbe =
        HostResourceProbe {
            events += "host"
            host
        }
    private val measured = SessionCost(bytesPerSession = 120 * MIB, bytesPerBrowser = 300 * MIB, measured = true)

    private fun costProbe(result: () -> SessionCost = { measured }) =
        SessionCostProbe { sessions, url ->
            events += "measure $sessions ${url ?: "blank"}"
            result()
        }

    @Test
    fun `without a measurement the host is probed and the estimate is used`() =
        runTest {
            val advice = RecommendCapacityUseCase(hostProbe).execute(contextsPerBrowser = 20)

            advice.perSession shouldBe SessionCost.ESTIMATE
            advice.host shouldBe host
            advice.maxTesters shouldBe 39
            events shouldContainExactly listOf("host")
        }

    @Test
    fun `a measurement probes the host first and then uses the measured cost`() =
        runTest {
            val useCase = RecommendCapacityUseCase(hostProbe, costProbe())

            val advice = useCase.execute(contextsPerBrowser = 20, Measurement(sessions = 3, url = URI("https://staging.kadrohr.test")))

            advice.perSession shouldBe measured
            events shouldContainExactly listOf("host", "measure 3 https://staging.kadrohr.test")
        }

    @Test
    fun `the default measurement opens five sessions on a blank page`() =
        runTest {
            RecommendCapacityUseCase(hostProbe, costProbe()).execute(contextsPerBrowser = 20, Measurement())

            events shouldContainExactly listOf("host", "measure 5 blank")
        }

    @Test
    fun `a failed measurement falls back to the estimate and says why`() =
        runTest {
            val failing = costProbe { throw CapacityMeasurementException("process memory cannot be measured here: /proc is not available") }

            val advice = RecommendCapacityUseCase(hostProbe, failing).execute(contextsPerBrowser = 20, Measurement())

            advice.perSession shouldBe SessionCost.ESTIMATE
            advice.notes.first() shouldBe
                "Measuring failed (process memory cannot be measured here: /proc is not available); the estimates are used."
            advice.notes.drop(1) shouldBe RecommendCapacityUseCase(hostProbe).execute(20).notes
        }

    @Test
    fun `cancellation while measuring is never turned into advice`() =
        runTest {
            val cancelled = costProbe { throw CancellationException("the user pressed Ctrl+C") }

            shouldThrow<CancellationException> {
                RecommendCapacityUseCase(hostProbe, cancelled).execute(contextsPerBrowser = 20, Measurement())
            }
        }

    @Test
    fun `asking for a measurement without a cost probe is a wiring error`() =
        runTest {
            shouldThrow<IllegalStateException> { RecommendCapacityUseCase(hostProbe).execute(20, Measurement()) }
                .message shouldStartWith "measuring the session cost needs a SessionCostProbe"
        }

    @Test
    fun `at least one session must be measured`() {
        shouldThrow<IllegalArgumentException> { Measurement(sessions = 0) }
        Measurement().sessions shouldBe 5
        events.shouldBeEmpty()
    }
}
