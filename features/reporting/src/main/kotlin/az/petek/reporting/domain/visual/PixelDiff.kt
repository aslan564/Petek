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
import java.util.BitSet

/**
 * Two captures as they are compared, and whether each one stops above the end of its page (rows at the other capture's
 * bottom may then be below this one's cap, not missing from its page). When they were taken with different height caps
 * and a cap cut one of them, both are cut to that height; a capture that reached the end of its page sets no height.
 */
class CapturePair(
    val before: Raster,
    val after: Raster,
    val beforeCapped: Boolean,
    val afterCapped: Boolean,
) {
    companion object {
        fun of(
            before: Raster,
            beforePageHeight: Int,
            beforeMaxHeight: Int,
            after: Raster,
            afterPageHeight: Int,
            afterMaxHeight: Int,
        ): CapturePair {
            val height =
                if (beforeMaxHeight == afterMaxHeight) {
                    Int.MAX_VALUE
                } else {
                    minOf(
                        if (beforePageHeight > before.height) before.height else Int.MAX_VALUE,
                        if (afterPageHeight > after.height) after.height else Int.MAX_VALUE,
                    )
                }
            val b = before.top(height)
            val a = after.top(height)
            return CapturePair(b, a, beforePageHeight > b.height, afterPageHeight > a.height)
        }
    }
}

/** What [PixelDiff.compare] found, in the current capture's coordinates. */
class PixelDiffResult internal constructor(
    val width: Int,
    val height: Int,
    private val differing: BitSet,
    /** Cells with at least [VisualThresholds.cellPixels] differing pixels. */
    val changedCells: CellMask,
    /** The cells of counted regions. */
    val countedCells: CellMask,
    /** Regions of at least [VisualThresholds.regionCells] cells, the run's own content included (not counted). */
    val regions: List<ChangedRegion>,
    val bands: List<Band>,
    val comparedPixels: Long,
    val differingPixels: Long,
) {
    fun differs(
        x: Int,
        y: Int,
    ): Boolean = x in 0 until width && y in 0 until height && differing[y * width + x]

    val countedRegions: List<ChangedRegion> get() = regions.filter { it.counted }
    val countedBands: List<Band> get() = bands.filter { it.counted }

    /** Differing pixels of counted regions. */
    val changedPixels: Long get() = countedRegions.sumOf { it.pixels }

    /** The pages differ: a counted region or a counted band. */
    val changed: Boolean get() = countedRegions.isNotEmpty() || countedBands.isNotEmpty()
}

/**
 * The pixel test of two aligned captures (docs/adr/0014). For each current row paired with a baseline row
 * ([Alignment]) and each column, a pixel is skipped when either side ignores it (its masks or noise, each side in its
 * own coordinates, so a mask of one release also hides the other's pixels it is compared with). Otherwise:
 *
 * - it is the same when the colours are equal, or within the YIQ distance ([VisualThresholds.maxDelta], both blended
 *   over white);
 * - it is the same when each side's colour is found within [VisualThresholds.shiftRadius] pixels of the same place on
 *   the other side, or lies between the colours there channel by channel (both ways), so anti-aliasing and a subpixel
 *   shift, which mix the colours next to an edge, are absorbed. A solid recolour, or a move of two pixels or more, still
 *   differs; a genuine one-pixel move of an edge goes unnoticed by design;
 * - otherwise it differs.
 *
 * Cells of [VisualThresholds.cell] pixels with [VisualThresholds.cellPixels] differing pixels are changed; touching
 * changed cells are regions, counted from [VisualThresholds.regionCells] cells unless the region meets the run's own
 * texts (either side's, the baseline's mapped through the alignment). Bands count from [VisualThresholds.bandRows]
 * compared rows, unless they are the run's own content or a cap artifact: rows at the bottom of one capture that the
 * other capture's height cap may have cut off (an inserted band at the current capture's bottom when the baseline was
 * capped, a removed band at the baseline's bottom when the current capture was).
 */
object PixelDiff {
    fun compare(
        before: Raster,
        after: Raster,
        alignment: Alignment,
        ignoreBefore: PixelMask,
        ignoreAfter: PixelMask,
        runTextBefore: List<LookBox>,
        runTextAfter: List<LookBox>,
        beforeCapped: Boolean,
        afterCapped: Boolean,
        t: VisualThresholds,
    ): PixelDiffResult {
        val width = after.width
        val height = after.height
        val cells = CellMask(width, height, t.cell)
        val cellCounts = IntArray(cells.columns * cells.rows)
        val differing = BitSet()
        var compared = 0L
        var differingCount = 0L
        val maxDelta = t.maxDelta
        for (ya in 0 until height) {
            val yb = alignment.beforeOf(ya)
            if (yb < 0) continue
            for (x in 0 until width) {
                if (ignoreAfter.covers(x, ya) || ignoreBefore.covers(x, yb)) continue
                compared++
                val differs =
                    if (x >= before.width) {
                        true
                    } else {
                        val a = after.argb[ya * width + x]
                        val b = before.argb[yb * before.width + x]
                        a != b && delta(a, b) > maxDelta &&
                            !(near(before, x, yb, a, t.shiftRadius, maxDelta) && near(after, x, ya, b, t.shiftRadius, maxDelta))
                    }
                if (differs) {
                    differing.set(ya * width + x)
                    differingCount++
                    cellCounts[(ya / t.cell) * cells.columns + x / t.cell]++
                }
            }
        }
        cellCounts.forEachIndexed { i, count -> if (count >= t.cellPixels) cells.set(i % cells.columns, i / cells.columns) }
        val beforeTexts = runTextBefore.map { mapped(it, alignment) }
        val texts = runTextAfter + beforeTexts
        val counted = CellMask(width, height, t.cell)
        val regions =
            cells.components().filter { it.size >= t.regionCells }.map { component ->
                val (box, pixels) = extent(component, cells, differing, width, height)
                val runContent = texts.any { meets(grown(box, t.cell), it) }
                if (!runContent) component.forEach { counted.set(it % cells.columns, it / cells.columns) }
                ChangedRegion(box, pixels, component.size, runContent)
            }
        val bands =
            alignment.bands.map { band ->
                judged(band, before.height, height, width, ignoreBefore, ignoreAfter, runTextBefore, texts, beforeCapped, afterCapped, t)
            }
        return PixelDiffResult(width, height, differing, cells, counted, regions, bands, compared, differingCount)
    }

    /**
     * pixelmatch's squared YIQ distance of two colours, each blended over white first. The coefficients are
     * pixelmatch's; [VisualThresholds.maxDelta] uses its 35215 scale.
     */
    fun delta(
        a: Int,
        b: Int,
    ): Double {
        val r1 = blend(a ushr RED, a ushr ALPHA)
        val g1 = blend(a ushr GREEN, a ushr ALPHA)
        val b1 = blend(a, a ushr ALPHA)
        val r2 = blend(b ushr RED, b ushr ALPHA)
        val g2 = blend(b ushr GREEN, b ushr ALPHA)
        val b2 = blend(b, b ushr ALPHA)
        val y = luma(r1, g1, b1) - luma(r2, g2, b2)
        val i = (r1 - r2) * 0.59597799 - (g1 - g2) * 0.27417610 - (b1 - b2) * 0.32180189
        val q = (r1 - r2) * 0.21147017 - (g1 - g2) * 0.52261711 + (b1 - b2) * 0.31114694
        return 0.5053 * y * y + 0.299 * i * i + 0.1957 * q * q
    }

    /** Luma (YIQ's Y) of a colour already blended over white. */
    fun luma(
        r: Double,
        g: Double,
        b: Double,
    ): Double = r * 0.29889531 + g * 0.58662247 + b * 0.11448223

    /** One channel over white: `255 + (c − 255) × alpha / 255`. */
    fun blend(
        channel: Int,
        alpha: Int,
    ): Double = MAX + ((channel and MASK) - MAX) * (alpha and MASK) / MAX

    /**
     * Whether [color] is found in [raster] within [radius] of ([x], [y]) (the place itself excluded), or lies between
     * the colours there (the place included), each channel blended over white within the neighbourhood's range.
     */
    private fun near(
        raster: Raster,
        x: Int,
        y: Int,
        color: Int,
        radius: Int,
        maxDelta: Double,
    ): Boolean {
        var low = CHANNELS_HIGH
        var high = CHANNELS_LOW
        for (dy in -radius..radius) {
            val ny = y + dy
            if (ny !in 0 until raster.height) continue
            for (dx in -radius..radius) {
                val nx = x + dx
                if (nx !in 0 until raster.width) continue
                val c = raster.argb[ny * raster.width + nx]
                if (!(dx == 0 && dy == 0) && (c == color || delta(c, color) <= maxDelta)) return true
                val r = channel(c, RED)
                val g = channel(c, GREEN)
                val b = channel(c, 0)
                low = packed(minOf(r, red(low)), minOf(g, green(low)), minOf(b, blue(low)))
                high = packed(maxOf(r, red(high)), maxOf(g, green(high)), maxOf(b, blue(high)))
            }
        }
        val r = channel(color, RED)
        val g = channel(color, GREEN)
        val b = channel(color, 0)
        return r in red(low)..red(high) && g in green(low)..green(high) && b in blue(low)..blue(high)
    }

    /** One channel of [color] blended over white, 0..255. */
    private fun channel(
        color: Int,
        shift: Int,
    ): Int {
        val alpha = color ushr ALPHA and MASK
        return MASK + ((color ushr shift and MASK) - MASK) * alpha / MASK
    }

    private fun packed(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (r shl RED) or (g shl GREEN) or b

    private fun red(packed: Int) = packed ushr RED and MASK

    private fun green(packed: Int) = packed ushr GREEN and MASK

    private fun blue(packed: Int) = packed and MASK

    /** The bounding box of a component's differing pixels and their count. */
    private fun extent(
        component: IntArray,
        cells: CellMask,
        differing: BitSet,
        width: Int,
        height: Int,
    ): Pair<LookBox, Long> {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        var pixels = 0L
        component.forEach { index ->
            val cx = index % cells.columns
            val cy = index / cells.columns
            for (y in cy * cells.cell until minOf((cy + 1) * cells.cell, height)) {
                for (x in cx * cells.cell until minOf((cx + 1) * cells.cell, width)) {
                    if (!differing[y * width + x]) continue
                    pixels++
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        return LookBox(x0, y0, x1 - x0 + 1, y1 - y0 + 1) to pixels
    }

    private fun judged(
        band: Band,
        beforeHeight: Int,
        afterHeight: Int,
        width: Int,
        ignoreBefore: PixelMask,
        ignoreAfter: PixelMask,
        runTextBefore: List<LookBox>,
        textsAfter: List<LookBox>,
        beforeCapped: Boolean,
        afterCapped: Boolean,
        t: VisualThresholds,
    ): Band {
        val inserted = band.kind == BandKind.INSERTED
        val ignore = if (inserted) ignoreAfter else ignoreBefore
        val liveRows = (band.y until band.y + band.height).count { ignore.anyOpen(it) }
        val own = LookBox(0, band.y - t.cell, width, band.height + 2 * t.cell)
        val runContent =
            if (inserted) {
                textsAfter.any { meets(own, it) }
            } else {
                runTextBefore.any { meets(own, it) } || textsAfter.any { meets(LookBox(0, band.at - t.cell, width, 2 * t.cell), it) }
            }
        // A band's rows may be below the other capture's cap, not missing from the other page.
        val cutByLimit =
            if (inserted) {
                beforeCapped && band.y + band.height >= afterHeight
            } else {
                afterCapped && band.y + band.height >= beforeHeight
            }
        return band.copy(
            liveRows = liveRows,
            runContent = runContent,
            cutByLimit = cutByLimit,
            counted = liveRows >= t.bandRows && !runContent && !cutByLimit,
        )
    }

    /** A baseline box in the current capture's rows, through the alignment. */
    private fun mapped(
        box: LookBox,
        alignment: Alignment,
    ): LookBox {
        val top = alignment.afterRowFor(box.y)
        val bottom = alignment.afterRowFor(box.y + box.height - 1)
        return LookBox(box.x, minOf(top, bottom), box.width, kotlin.math.abs(bottom - top) + 1)
    }

    private fun grown(
        box: LookBox,
        by: Int,
    ) = LookBox(box.x - by, box.y - by, box.width + 2 * by, box.height + 2 * by)

    /** Whether two boxes share at least one pixel. */
    fun meets(
        a: LookBox,
        b: LookBox,
    ): Boolean = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    private const val MAX = 255.0
    private const val MASK = 0xff
    private const val ALPHA = 24
    private const val RED = 16
    private const val GREEN = 8
    private const val CHANNELS_HIGH = 0xffffff
    private const val CHANNELS_LOW = 0
}
