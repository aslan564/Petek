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

import az.petek.evidence.domain.LookBox
import az.petek.reporting.LookTestData.BLUE
import az.petek.reporting.LookTestData.RED
import az.petek.reporting.LookTestData.inserted
import az.petek.reporting.LookTestData.page
import az.petek.reporting.LookTestData.painted
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LookCropsTest {
    private val t = VisualThresholds()

    @Test
    fun `crops stay within 1200 by 800 and the before crop starts at the aligned row`() {
        // A block recoloured across the whole width, below 20 rows the new release inserted near the top.
        val page = page(1_300, 1_000)
        val before = page.painted(40, 200, 1_250, 700, RED)
        val after = page.painted(40, 200, 1_250, 700, BLUE).inserted(100, page(1_300, 20, seed = 3))
        val none = PixelMask.none(before.width, before.height)
        val noneAfter = PixelMask.none(after.width, after.height)
        val alignment = RowAlignment.align(before, after, none, noneAfter)
        val result = PixelDiff.compare(before, after, alignment, none, noneAfter, emptyList(), emptyList(), false, false, t)

        val windows = LookCrops.windows(result, alignment, before.width, before.height)

        windows shouldHaveSize 2
        val region = windows.first()
        region.box shouldBe LookBox(40, 220, 1_250, 700)
        region.after shouldBe LookBox(8, 188, 1_200, 764)
        region.before.y shouldBe alignment.beforeRowFor(region.after.y)
        region.before.y shouldBe 168
        region.before.height shouldBeLessThanOrEqual 800
        windows.forEach {
            it.after.width shouldBeLessThanOrEqual 1_200
            it.after.height shouldBeLessThanOrEqual 800
        }
        val crop = LookCrops.cut(before, after, after, region)
        crop.before.width shouldBe 1_200
        crop.after.height shouldBe 764
    }
}
