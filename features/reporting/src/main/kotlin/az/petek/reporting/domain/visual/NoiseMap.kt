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

/**
 * Where a page changes by itself (docs/adr/0014): the cells in which [main] differs from [other], a capture of the
 * same page in the same run (another frame of the load, the reloaded page, another tester's capture), in [main]'s
 * coordinates. The same alignment and pixel test as a comparison, but any differing pixel marks its cell
 * ([VisualThresholds.noiseCellPixels]), every row of an uneven hunk is noise across the full width (content that
 * grows and shrinks), and the result is grown by [VisualThresholds.noiseGrow] cells.
 *
 * A side's moving map is the union over its frames; its peer map the union over the other testers' captures. Both are
 * ignored when that side is compared with the other release.
 */
object NoiseMap {
    fun between(
        main: Raster,
        other: Raster,
        ignoreMain: PixelMask,
        ignoreOther: PixelMask,
        t: VisualThresholds,
    ): CellMask {
        val alignment = RowAlignment.align(other, main, ignoreOther, ignoreMain)
        val noise = t.copy(cellPixels = t.noiseCellPixels)
        val diff = PixelDiff.compare(other, main, alignment, ignoreOther, ignoreMain, emptyList(), emptyList(), false, false, noise)
        val cells = CellMask(main.width, main.height, t.cell)
        diff.changedCells.forEachSet(cells::set)
        alignment.hunks.filterNot { it.even }.forEach { hunk ->
            if (hunk.afterEnd > hunk.afterStart) {
                cells.setRows(hunk.afterStart, hunk.afterEnd)
            } else {
                // Rows only the other capture has: the row where they were.
                val row = hunk.afterStart.coerceAtMost(main.height - 1)
                cells.setRows(row, row + 1)
            }
        }
        return cells.grow(t.noiseGrow)
    }
}
