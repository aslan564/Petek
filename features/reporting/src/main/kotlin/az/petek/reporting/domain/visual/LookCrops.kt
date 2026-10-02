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

/** Where to cut one crop: [box] is the changed area (its own capture's coordinates), [before] and [after] the windows. */
data class CropWindow(
    val number: Int,
    val box: LookBox,
    val before: LookBox,
    val after: LookBox,
)

/** The three pictures of one crop: the baseline's, the current run's and the difference picture's. */
class Crop(
    val before: Raster,
    val after: Raster,
    val diff: Raster,
)

/**
 * Crops of a changed look's largest counted areas ([MAX_CROPS] at most): each window is the area padded by [PADDING]
 * pixels, clipped to its capture and cut to the top-left [MAX_WIDTH] × [MAX_HEIGHT]. The baseline crop takes the same
 * columns from the baseline row aligned with the window's top ([Alignment.beforeRowFor]), so both show the same
 * content; for removed rows the window is centred on them in the baseline and the current crop shows the same rows
 * around the place they were. The difference crop is cut from the overlay with the current window.
 */
object LookCrops {
    const val MAX_CROPS = 4
    const val PADDING = 32
    const val MAX_WIDTH = 1_200
    const val MAX_HEIGHT = 800

    fun windows(
        result: PixelDiffResult,
        alignment: Alignment,
        beforeWidth: Int,
        beforeHeight: Int,
    ): List<CropWindow> {
        val afterWidth = result.width
        val afterHeight = result.height
        val regions = result.countedRegions.map { Area(it.box, it.pixels, null) }
        val bands =
            result.countedBands.map { band ->
                val width = if (band.kind == BandKind.REMOVED) beforeWidth else afterWidth
                Area(LookBox(0, band.y, width, band.height), band.height.toLong() * width, band)
            }
        val areas = regions + bands
        return areas
            .sortedByDescending { it.size }
            .take(MAX_CROPS)
            .mapIndexed { i, area ->
                if (area.band?.kind == BandKind.REMOVED) {
                    val before = centred(clip(padded(area.box), beforeWidth, beforeHeight), area.box)
                    val after =
                        clip(
                            LookBox(before.x, area.band.at - (area.box.y - before.y), before.width, before.height),
                            afterWidth,
                            afterHeight,
                        )
                    CropWindow(i + 1, area.box, before, after)
                } else {
                    val after = limited(clip(padded(area.box), afterWidth, afterHeight))
                    val before =
                        clip(LookBox(after.x, alignment.beforeRowFor(after.y), after.width, after.height), beforeWidth, beforeHeight)
                    CropWindow(i + 1, area.box, before, after)
                }
            }
    }

    fun cut(
        before: Raster,
        after: Raster,
        overlay: Raster,
        window: CropWindow,
    ): Crop = Crop(before.crop(window.before), after.crop(window.after), overlay.crop(window.after))

    private class Area(
        val box: LookBox,
        val size: Long,
        val band: Band?,
    )

    private fun padded(box: LookBox) = LookBox(box.x - PADDING, box.y - PADDING, box.width + 2 * PADDING, box.height + 2 * PADDING)

    /** The top-left part of a window larger than [MAX_WIDTH] × [MAX_HEIGHT]. */
    private fun limited(box: LookBox) = LookBox(box.x, box.y, minOf(box.width, MAX_WIDTH), minOf(box.height, MAX_HEIGHT))

    /** A window no larger than the limits, its rows centred on [area]'s middle when it must be cut. */
    private fun centred(
        window: LookBox,
        area: LookBox,
    ): LookBox {
        if (window.height <= MAX_HEIGHT) return limited(window)
        val middle = area.y + area.height / 2
        val top = (middle - MAX_HEIGHT / 2).coerceIn(window.y, window.y + window.height - MAX_HEIGHT)
        return LookBox(window.x, top, minOf(window.width, MAX_WIDTH), MAX_HEIGHT)
    }

    /** [box] inside a [width] × [height] capture; a window below its bottom keeps its last row. */
    private fun clip(
        box: LookBox,
        width: Int,
        height: Int,
    ): LookBox {
        val x0 = box.x.coerceIn(0, maxOf(width - 1, 0))
        val y0 = box.y.coerceIn(0, maxOf(height - 1, 0))
        val x1 = (box.x + box.width).coerceIn(x0, width)
        val y1 = (box.y + box.height).coerceIn(y0, height)
        return LookBox(x0, y0, maxOf(x1 - x0, minOf(1, width - x0)), maxOf(y1 - y0, minOf(1, height - y0)))
    }
}
