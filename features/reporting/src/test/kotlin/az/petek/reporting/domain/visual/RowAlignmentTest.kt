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
import az.petek.reporting.LookTestData.blank
import az.petek.reporting.LookTestData.inserted
import az.petek.reporting.LookTestData.page
import az.petek.reporting.LookTestData.painted
import az.petek.reporting.LookTestData.removed
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RowAlignmentTest {
    private fun align(
        before: Raster,
        after: Raster,
        ignoreBefore: PixelMask = PixelMask.none(before.width, before.height),
        ignoreAfter: PixelMask = PixelMask.none(after.width, after.height),
    ) = RowAlignment.align(before, after, ignoreBefore, ignoreAfter)

    @Test
    fun `identical pages align row by row`() {
        val page = page(40, 100)

        val alignment = align(page, page)

        (0 until 100).map(alignment::beforeOf) shouldContainExactly (0 until 100).toList()
        alignment.bands.shouldBeEmpty()
    }

    @Test
    fun `a band inserted near the top is one inserted band and the rows below pair with their old rows`() {
        val before = page(40, 100)
        val after = before.inserted(10, page(40, 20, seed = 2))

        val alignment = align(before, after)

        alignment.bands shouldContainExactly listOf(Band(BandKind.INSERTED, 10, 20, 10))
        (0 until 10).map(alignment::beforeOf) shouldContainExactly (0 until 10).toList()
        (30 until 120).map(alignment::beforeOf) shouldContainExactly (10 until 100).toList()
        alignment.beforeOf(15) shouldBe -1
    }

    @Test
    fun `rows removed from the baseline are a removed band at the baseline's height`() {
        val before = page(40, 100)
        val after = before.removed(50, 20)

        val alignment = align(before, after)

        alignment.bands shouldContainExactly listOf(Band(BandKind.REMOVED, 50, 20, 50))
        alignment.beforeOf(49) shouldBe 49
        alignment.beforeOf(50) shouldBe 70
        alignment.afterRowFor(75) shouldBe 55
    }

    @Test
    fun `blank rows never anchor the alignment`() {
        // Five rows of one colour each, then three rows of content; the new release puts the content first. Were the
        // single-colour rows anchors, their longer run would win and the content would look inserted and removed.
        val colours = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt())
        val solid = colours.map { blank(40, 1, it) }.reduce { acc, row -> acc.inserted(acc.height, row) }
        val content = page(40, 3, seed = 7)
        val before = solid.inserted(5, content)
        val after = content.inserted(3, solid)

        val alignment = align(before, after)

        (0 until 3).map(alignment::beforeOf) shouldContainExactly listOf(5, 6, 7)
    }

    @Test
    fun `without a shared unique row the rows pair from the top and the rest is a band at the bottom`() {
        val alignment = align(blank(40, 50), blank(40, 60))

        (0 until 50).map(alignment::beforeOf) shouldContainExactly (0 until 50).toList()
        alignment.bands shouldContainExactly listOf(Band(BandKind.INSERTED, 50, 10, 50))
    }

    @Test
    fun `rows hidden by a side's own masks hash alike`() {
        // Only rows 20..29 carry content (a dot of its own per row), and a clock in them differs; five new rows open the
        // new release.
        val content = (20 until 30).fold(blank(40, 60)) { raster, y -> raster.painted(35, y, 1, 1, 0xFF000000.toInt() or y) }
        val before = content.painted(0, 20, 8, 10, 0xFFAA0000.toInt())
        val after = content.painted(0, 20, 8, 10, 0xFF00AA00.toInt()).inserted(0, page(40, 5, seed = 3))
        val clockBefore = PixelMask.of(40, 60, listOf(LookBox(0, 20, 8, 10)))
        val clockAfter = PixelMask.of(40, 65, listOf(LookBox(0, 25, 8, 10)))

        val masked = align(before, after, clockBefore, clockAfter)
        val unmasked = align(before, after)

        masked.beforeOf(25) shouldBe 20
        masked.beforeOf(34) shouldBe 29
        unmasked.beforeOf(25) shouldBe 25
    }
}
