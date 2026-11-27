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
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LookOverlayTest {
    private val t = VisualThresholds()

    /** A recoloured block, ten new rows below it, a mask and a noise cell: one of each thing the overlay draws. */
    private val page = page(80, 100)
    private val before = page.painted(10, 30, 16, 16, RED)
    private val after = page.painted(10, 30, 16, 16, BLUE).inserted(60, page(80, 10, seed = 8))
    private val masks = PixelMask.of(80, 110, listOf(LookBox(40, 4, 10, 10)))
    private val noise = CellMask(80, 110, t.cell).apply { set(7, 2) }

    private fun render(): Raster {
        val ignoreBefore = PixelMask.none(80, 100)
        val ignoreAfter = masks.with(noise)
        val alignment = RowAlignment.align(before, after, ignoreBefore, ignoreAfter)
        val result = PixelDiff.compare(before, after, alignment, ignoreBefore, ignoreAfter, emptyList(), emptyList(), false, false, t)
        return LookOverlay.render(after, result, masks, noise)
    }

    private fun channels(color: Int) = Triple(color ushr 16 and 0xff, color ushr 8 and 0xff, color and 0xff)

    @Test
    fun `the overlay of the same inputs is the same pixels every time`() {
        render().argb.contentEquals(render().argb) shouldBe true
    }

    @Test
    fun `counted pixels are red, masks blue, noise yellow, inserted rows green and regions boxed`() {
        val overlay = render()

        overlay[15, 35] shouldBe LookOverlay.COUNTED
        // The region's box is (10, 30) 16 × 16; its outline is drawn two pixels outside it.
        overlay[8, 35] shouldBe LookOverlay.COUNTED
        overlay[20, 29] shouldBe LookOverlay.COUNTED
        channels(overlay[45, 8]).let { (r, g, b) -> (b > g && g > r) shouldBe true }
        channels(overlay[60, 20]).let { (r, g, b) -> (r > g && g > b) shouldBe true }
        channels(overlay[40, 65]).let { (r, g, b) -> (g > b && b > r) shouldBe true }
        // Elsewhere the current capture in light grey.
        channels(overlay[60, 90]).let { (r, g, b) -> (r == g && g == b && r > 200) shouldBe true }
    }
}
