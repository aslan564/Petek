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

package az.petek.reporting.domain.visual

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VisualThresholdsTest {
    @Test
    fun `thresholds refuse nonsense values`() {
        listOf(
            { VisualThresholds(colorDelta = 0.0) },
            { VisualThresholds(colorDelta = 1.5) },
            { VisualThresholds(shiftRadius = -1) },
            { VisualThresholds(cell = 1) },
            { VisualThresholds(cellPixels = 0) },
            { VisualThresholds(cellPixels = 65) },
            { VisualThresholds(regionCells = 0) },
            { VisualThresholds(bandRows = 0) },
            { VisualThresholds(halo = -1) },
            { VisualThresholds(noiseCellPixels = 0) },
            { VisualThresholds(noiseGrow = -1) },
            { VisualThresholds(movingLimit = 0.0) },
            { VisualThresholds(ignoredLimit = 1.1) },
            { VisualThresholds(heightFact = -1) },
            { VisualThresholds(maxSamples = 0) },
        ).forEach { shouldThrow<IllegalArgumentException> { it() } }

        VisualThresholds().maxDelta shouldBe 35_215.0 * 0.10 * 0.10
    }
}
