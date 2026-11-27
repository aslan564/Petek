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

import az.petek.reporting.LookTestData.BLACK
import az.petek.reporting.LookTestData.blank
import az.petek.reporting.LookTestData.inserted
import az.petek.reporting.LookTestData.page
import az.petek.reporting.LookTestData.painted
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NoiseMapTest {
    private val t = VisualThresholds()

    @Test
    fun `cells that differ between two frames of one load are noise, grown by one cell`() {
        val main = blank(64, 64)
        val other = main.painted(26, 26, 2, 2, BLACK)

        val noise = NoiseMap.between(main, other, PixelMask.none(64, 64), PixelMask.none(64, 64), t)

        noise.count() shouldBe 9
        (2..4).all { cx -> (2..4).all { cy -> noise.has(cx, cy) } } shouldBe true
        noise.has(1, 1) shouldBe false
        noise.has(5, 3) shouldBe false
    }

    @Test
    fun `rows of an unequal hunk between two samples are noise across the width`() {
        val other = page(40, 100)
        val main = other.inserted(50, page(40, 10, seed = 6))

        val noise = NoiseMap.between(main, other, PixelMask.none(40, 110), PixelMask.none(40, 100), t)

        // Rows 50..59 are cell rows 6 and 7, grown to 5..8.
        (0 until noise.columns).all { cx -> (5..8).all { cy -> noise.has(cx, cy) } } shouldBe true
        noise.has(0, 3) shouldBe false
        noise.has(0, 10) shouldBe false
    }
}
