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

import java.util.BitSet

/**
 * Square cells of [cell] pixels over a [width] × [height] capture, set or not: changed cells of a comparison, or the
 * cells where a page changed by itself (noise). The last column and row may be partial.
 */
class CellMask(
    val width: Int,
    val height: Int,
    val cell: Int,
) {
    init {
        require(cell > 0) { "cell must be positive, was $cell" }
        require(width >= 0 && height >= 0) { "size must not be negative, was ${width}x$height" }
    }

    val columns: Int = (width + cell - 1) / cell
    val rows: Int = (height + cell - 1) / cell
    private val bits = BitSet(columns * rows)

    fun set(
        cx: Int,
        cy: Int,
    ) {
        if (cx in 0 until columns && cy in 0 until rows) bits.set(cy * columns + cx)
    }

    fun has(
        cx: Int,
        cy: Int,
    ): Boolean = cx in 0 until columns && cy in 0 until rows && bits[cy * columns + cx]

    /** Whether the cell holding pixel ([x], [y]) is set. */
    fun coversPixel(
        x: Int,
        y: Int,
    ): Boolean = x >= 0 && y >= 0 && has(x / cell, y / cell)

    /** Sets every cell of pixel rows `[y0, y1)` across the full width. */
    fun setRows(
        y0: Int,
        y1: Int,
    ) {
        if (y1 <= y0) return
        val from = (y0.coerceAtLeast(0) / cell)
        val to = ((y1 - 1).coerceAtMost(height - 1) / cell)
        for (cy in from..to) if (cy in 0 until rows) bits.set(cy * columns, cy * columns + columns)
    }

    fun count(): Int = bits.cardinality()

    /** The share of all cells that are set (0 for an empty capture). */
    fun share(): Double = if (columns * rows == 0) 0.0 else count().toDouble() / (columns * rows)

    fun forEachSet(action: (cx: Int, cy: Int) -> Unit) {
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            action(i % columns, i / columns)
            i = bits.nextSetBit(i + 1)
        }
    }

    /** This mask and [other] (of the same grid) together. */
    fun or(other: CellMask): CellMask {
        require(other.columns == columns && other.rows == rows && other.cell == cell) { "cell masks of different grids" }
        return copy().also { it.bits.or(other.bits) }
    }

    /** Every set cell grown by [by] cells in each direction, diagonals included. */
    fun grow(by: Int): CellMask {
        if (by <= 0) return copy()
        val out = CellMask(width, height, cell)
        forEachSet { cx, cy ->
            for (y in (cy - by).coerceAtLeast(0)..(cy + by).coerceAtMost(rows - 1)) {
                val x0 = (cx - by).coerceAtLeast(0)
                val x1 = (cx + by).coerceAtMost(columns - 1)
                out.bits.set(y * columns + x0, y * columns + x1 + 1)
            }
        }
        return out
    }

    /** Groups of set cells that touch, diagonals included; each group lists its cells as `cy * columns + cx`. */
    fun components(): List<IntArray> {
        val seen = BitSet(columns * rows)
        val out = mutableListOf<IntArray>()
        val queue = IntArray(count())
        var start = bits.nextSetBit(0)
        while (start >= 0) {
            if (!seen[start]) {
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen.set(start)
                while (head < tail) {
                    val at = queue[head++]
                    val cx = at % columns
                    val cy = at / columns
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx !in 0 until columns || ny !in 0 until rows) continue
                            val next = ny * columns + nx
                            if (bits[next] && !seen[next]) {
                                seen.set(next)
                                queue[tail++] = next
                            }
                        }
                    }
                }
                out += queue.copyOf(tail)
            }
            start = bits.nextSetBit(start + 1)
        }
        return out
    }

    private fun copy(): CellMask = CellMask(width, height, cell).also { it.bits.or(bits) }
}
