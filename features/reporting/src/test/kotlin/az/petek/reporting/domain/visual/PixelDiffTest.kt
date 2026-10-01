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
import az.petek.reporting.LookTestData.BLACK
import az.petek.reporting.LookTestData.BLUE
import az.petek.reporting.LookTestData.RED
import az.petek.reporting.LookTestData.WHITE
import az.petek.reporting.LookTestData.blank
import az.petek.reporting.LookTestData.inserted
import az.petek.reporting.LookTestData.page
import az.petek.reporting.LookTestData.painted
import az.petek.reporting.LookTestData.removed
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PixelDiffTest {
    private val t = VisualThresholds()

    private fun diff(
        before: Raster,
        after: Raster,
        ignoreBefore: PixelMask = PixelMask.none(before.width, before.height),
        ignoreAfter: PixelMask = PixelMask.none(after.width, after.height),
        runTextBefore: List<LookBox> = emptyList(),
        runTextAfter: List<LookBox> = emptyList(),
        capped: Boolean = false,
    ): PixelDiffResult {
        val alignment = RowAlignment.align(before, after, ignoreBefore, ignoreAfter)
        return PixelDiff.compare(before, after, alignment, ignoreBefore, ignoreAfter, runTextBefore, runTextAfter, capped, capped, t)
    }

    /** Two captures as [CapturePair] cuts them, each side capped only when its page goes on below its capture. */
    private fun diff(pair: CapturePair): PixelDiffResult {
        val ignoreBefore = PixelMask.none(pair.before.width, pair.before.height)
        val ignoreAfter = PixelMask.none(pair.after.width, pair.after.height)
        val alignment = RowAlignment.align(pair.before, pair.after, ignoreBefore, ignoreAfter)
        return PixelDiff.compare(
            pair.before,
            pair.after,
            alignment,
            ignoreBefore,
            ignoreAfter,
            emptyList(),
            emptyList(),
            pair.beforeCapped,
            pair.afterCapped,
            t,
        )
    }

    @Test
    fun `identical rasters differ in no pixel`() {
        val page = page(60, 80).painted(20, 20, 20, 20, RED)

        val result = diff(page, page)

        result.differingPixels shouldBe 0
        result.comparedPixels shouldBe 60L * 80
        result.regions.shouldBeEmpty()
        result.changed shouldBe false
    }

    @Test
    fun `white and FDFDFD are the same colour under the 0,10 YIQ threshold`() {
        (PixelDiff.delta(WHITE, 0xFFFDFDFD.toInt()) <= t.maxDelta) shouldBe true
        (PixelDiff.delta(WHITE, 0xFFE0E0E0.toInt()) <= t.maxDelta) shouldBe false

        diff(blank(40, 40), blank(40, 40, 0xFFFDFDFD.toInt())).differingPixels shouldBe 0
    }

    @Test
    fun `an edge moved by one pixel is not a difference and moved by two pixels is`() {
        val before = blank(60, 40).painted(20, 8, 20, 24, BLACK)

        val byOne = diff(before, blank(60, 40).painted(21, 8, 20, 24, BLACK))
        val byTwo = diff(before, blank(60, 40).painted(22, 8, 20, 24, BLACK))

        byOne.differingPixels shouldBe 0
        byTwo.differingPixels shouldBeGreaterThan 0
        byTwo.changed shouldBe true
    }

    @Test
    fun `anti-aliased text rendered half a pixel apart is not a difference`() {
        // A stem whose left edge falls at x = 10.25 in one release and x = 10.75 in the other: the edge pixel is
        // three quarters ink, then a quarter.
        fun stem(edgeInk: Int) =
            blank(40, 24)
                .painted(10, 4, 1, 16, 0xFF000000.toInt() or (0x010101 * (255 - edgeInk)))
                .painted(11, 4, 10, 16, BLACK)
                .painted(21, 4, 1, 16, 0xFF000000.toInt() or (0x010101 * edgeInk))

        val result = diff(stem(191), stem(64))

        result.differingPixels shouldBe 0
    }

    @Test
    fun `a recoloured 40 by 40 block is one region with its exact box and pixel count`() {
        val page = blank(100, 100)

        val result = diff(page.painted(20, 30, 40, 40, RED), page.painted(20, 30, 40, 40, BLUE))

        result.regions shouldContainExactly listOf(ChangedRegion(LookBox(20, 30, 40, 40), 1_600, 36))
        result.changed shouldBe true
        result.changedPixels shouldBe 1_600
    }

    @Test
    fun `a single changed cell is speckle, counted but not a region`() {
        val result = diff(blank(64, 64), blank(64, 64).painted(17, 17, 3, 3, BLACK))

        result.differingPixels shouldBe 9
        result.changedCells.count() shouldBe 1
        result.regions.shouldBeEmpty()
        result.changed shouldBe false
    }

    @Test
    fun `pixels under either side's mask, mapped through the alignment, are never counted`() {
        val page = page(80, 120)
        val before = page.painted(20, 60, 30, 20, RED)
        val after = page.painted(20, 60, 30, 20, BLUE).inserted(10, page(80, 20, seed = 9))
        val beforeMask = PixelMask.of(80, 120, listOf(LookBox(20, 60, 30, 20)))
        val afterMask = PixelMask.of(80, 140, listOf(LookBox(20, 80, 30, 20)))

        val maskedBefore = diff(before, after, ignoreBefore = beforeMask)
        val maskedAfter = diff(before, after, ignoreAfter = afterMask)
        val unmasked = diff(before, after)

        maskedBefore.regions.shouldBeEmpty()
        maskedAfter.regions.shouldBeEmpty()
        unmasked.regions.map { it.box } shouldContainExactly listOf(LookBox(20, 80, 30, 20))
    }

    @Test
    fun `a mask added only in the new release hides that region in the baseline too, below an inserted band`() {
        val page = page(80, 120)
        val before = page.painted(30, 70, 20, 10, BLACK)
        val after = page.painted(30, 70, 20, 10, RED).inserted(5, page(80, 16, seed = 4))
        val clock = PixelMask.of(80, 136, listOf(LookBox(30, 86, 20, 10)), t.halo)

        val result = diff(before, after, ignoreAfter = clock)

        result.regions.shouldBeEmpty()
        result.bands shouldHaveSize 1
        result.bands.single().let {
            it.kind shouldBe BandKind.INSERTED
            it.y shouldBe 5
            it.counted shouldBe true
        }
    }

    @Test
    fun `a region touching the run's own text is run content and not counted`() {
        val page = blank(120, 60)

        val result =
            diff(
                page.painted(20, 20, 40, 16, BLACK),
                page.painted(20, 20, 40, 16, RED),
                runTextAfter = listOf(LookBox(64, 22, 30, 10)),
            )

        result.regions shouldHaveSize 1
        result.regions.single().runContent shouldBe true
        result.changed shouldBe false
    }

    @Test
    fun `a band at the bottom of a capture cut by its height limit is not counted`() {
        // Both captures stop at 100 rows; the new release inserts 20 rows near the top, so the baseline's last 20
        // rows fall below the new capture's cap.
        val before = page(40, 100)
        val after = before.inserted(10, page(40, 20, seed = 5)).let { Raster(40, 100, it.argb.copyOf(40 * 100)) }

        val result = diff(before, after, capped = true)

        result.bands.map { Triple(it.kind, it.counted, it.cutByLimit) } shouldContainExactly
            listOf(Triple(BandKind.INSERTED, true, false), Triple(BandKind.REMOVED, false, true))
    }

    @Test
    fun `rows appended past the current capture's cap to a baseline captured whole are a counted band`() {
        // The baseline page ends at 300 rows; the new release appends 200 rows, of which its 400-row cap keeps 100.
        val before = page(40, 300)
        val now = before.inserted(300, page(40, 200, seed = 5))
        val pair = CapturePair.of(before, 300, 400, now.top(400), 500, 400)

        val result = diff(pair)

        (pair.beforeCapped to pair.afterCapped) shouldBe (false to true)
        result.bands shouldContainExactly listOf(Band(BandKind.INSERTED, 300, 100, 300, counted = true))
        result.changed shouldBe true
    }

    @Test
    fun `rows removed from the end of a page whose baseline was cut by its cap are a counted band`() {
        // The baseline page has 500 rows, captured to its 400-row cap; the new release drops its last 200 rows and is
        // captured whole.
        val page = page(40, 500)
        val pair = CapturePair.of(page.top(400), 500, 400, page.top(300), 300, 400)

        val result = diff(pair)

        (pair.beforeCapped to pair.afterCapped) shouldBe (true to false)
        result.bands shouldContainExactly listOf(Band(BandKind.REMOVED, 300, 100, 300, counted = true))
        result.changed shouldBe true
    }

    @Test
    fun `rows below the baseline's cap are not counted when the current page, captured whole, lost rows above them`() {
        // The baseline page has 500 rows, captured to its 400-row cap; the new release drops 100 rows near the top, so
        // its last 100 rows are the ones the baseline's cap cut off.
        val page = page(40, 500)
        val pair = CapturePair.of(page.top(400), 500, 400, page.removed(50, 100), 400, 400)

        val result = diff(pair)

        (pair.beforeCapped to pair.afterCapped) shouldBe (true to false)
        result.bands.map { Triple(it.kind, it.counted, it.cutByLimit) } shouldContainExactly
            listOf(Triple(BandKind.REMOVED, true, false), Triple(BandKind.INSERTED, false, true))
    }
}
