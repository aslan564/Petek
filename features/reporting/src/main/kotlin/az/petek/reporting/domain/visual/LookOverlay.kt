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

import kotlin.math.roundToInt

/**
 * The difference picture of a changed look (the report's "Fərq"), drawn by code from the current capture and the
 * comparison, with no text, so the same inputs always give the same pixels:
 *
 * - the current capture in grey, lightened to 35 %;
 * - masks tinted blue and noise (what moved by itself) yellow, at 30 %;
 * - inserted rows tinted green at 25 %;
 * - counted differing pixels red, uncounted ones (speckle, the run's own content) orange;
 * - a 2 px violet line where baseline rows were removed;
 * - a 2 px red outline around every counted region.
 */
object LookOverlay {
    const val COUNTED = 0xFFE5484D.toInt()
    const val SPECKLE = 0xFFF59E0B.toInt()
    const val MASK = 0xFF3B82F6.toInt()
    const val NOISE = 0xFFEAB308.toInt()
    const val INSERTED = 0xFF22C55E.toInt()
    const val REMOVED = 0xFFD946EF.toInt()

    private const val LIGHTEN = 0.35
    private const val TINT = 0.30
    private const val INSERTED_TINT = 0.25
    private const val LINE = 2
    private const val OPAQUE = 0xFF shl 24
    private const val CHANNEL = 0xff

    fun render(
        after: Raster,
        result: PixelDiffResult,
        masks: PixelMask,
        noise: CellMask,
    ): Raster {
        val width = after.width
        val height = after.height
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                out[i] = grey(after.argb[i])
                if (masks.covers(x, y)) out[i] = tint(out[i], MASK, TINT)
                if (noise.coversPixel(x, y)) out[i] = tint(out[i], NOISE, TINT)
            }
        }
        result.bands.filter { it.kind == BandKind.INSERTED }.forEach { band ->
            for (y in band.y until minOf(band.y + band.height, height)) {
                for (x in 0 until width) out[y * width + x] = tint(out[y * width + x], INSERTED, INSERTED_TINT)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (result.differs(x, y)) out[y * width + x] = if (result.countedCells.coversPixel(x, y)) COUNTED else SPECKLE
            }
        }
        result.bands.filter { it.kind == BandKind.REMOVED }.forEach { band ->
            val top = band.at.coerceIn(0, maxOf(height - LINE, 0))
            for (y in top until minOf(top + LINE, height)) for (x in 0 until width) out[y * width + x] = REMOVED
        }
        result.countedRegions.forEach { region ->
            outline(out, width, height, region.box.x - LINE, region.box.y - LINE, region.box.width + 2 * LINE, region.box.height + 2 * LINE)
        }
        return Raster(width, height, out)
    }

    /** The colour's luma over white, lightened: `255 − (255 − luma) × 0.35`, opaque. */
    private fun grey(color: Int): Int {
        val alpha = color ushr 24
        val luma =
            PixelDiff.luma(
                PixelDiff.blend(color ushr 16, alpha),
                PixelDiff.blend(color ushr 8, alpha),
                PixelDiff.blend(color, alpha),
            )
        val v = (255 - (255 - luma) * LIGHTEN).roundToInt().coerceIn(0, 255)
        return OPAQUE or (v shl 16) or (v shl 8) or v
    }

    private fun tint(
        base: Int,
        color: Int,
        share: Double,
    ): Int {
        fun mix(shift: Int): Int {
            val b = base ushr shift and CHANNEL
            val c = color ushr shift and CHANNEL
            return (b * (1 - share) + c * share).roundToInt().coerceIn(0, 255)
        }
        return OPAQUE or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun outline(
        out: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        fun paint(
            px: Int,
            py: Int,
        ) {
            if (px in 0 until width && py in 0 until height) out[py * width + px] = COUNTED
        }
        for (i in 0 until LINE) {
            for (px in x until x + w) {
                paint(px, y + i)
                paint(px, y + h - 1 - i)
            }
            for (py in y until y + h) {
                paint(x + i, py)
                paint(x + w - 1 - i, py)
            }
        }
    }
}
