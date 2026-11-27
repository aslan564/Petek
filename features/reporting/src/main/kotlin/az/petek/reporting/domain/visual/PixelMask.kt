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
import az.petek.evidence.domain.LookMask
import java.util.BitSet

/**
 * The pixels of one capture that are not compared (its masks, grown by a halo, and what moved by itself), one bit
 * per pixel of a [width] × [height] capture. Immutable: [with] returns a new mask.
 */
class PixelMask private constructor(
    val width: Int,
    val height: Int,
    private val bits: BitSet,
) {
    /** False outside the capture. */
    fun covers(
        x: Int,
        y: Int,
    ): Boolean = x in 0 until width && y in 0 until height && bits[y * width + x]

    /** How many pixels are covered. */
    fun count(): Long = bits.cardinality().toLong()

    /** True when any pixel of row [y] in `[x0, x1)` is not covered. */
    fun anyOpen(
        y: Int,
        x0: Int = 0,
        x1: Int = width,
    ): Boolean {
        if (y !in 0 until height || x0 >= x1) return false
        val from = y * width + x0.coerceAtLeast(0)
        val to = y * width + x1.coerceAtMost(width)
        return bits.nextClearBit(from) < to
    }

    /** This mask and [other] (of the same size) together. */
    fun with(other: PixelMask): PixelMask {
        require(other.width == width && other.height == height) { "masks of different sizes" }
        return PixelMask(width, height, (bits.clone() as BitSet).apply { or(other.bits) })
    }

    /** This mask and every pixel of [cells]' set cells inside the capture. */
    fun with(cells: CellMask): PixelMask {
        if (cells.count() == 0) return this
        val out = bits.clone() as BitSet
        cells.forEachSet { cx, cy -> fill(out, width, height, cx * cells.cell, cy * cells.cell, cells.cell, cells.cell) }
        return PixelMask(width, height, out)
    }

    companion object {
        fun none(
            width: Int,
            height: Int,
        ): PixelMask = PixelMask(width, height, BitSet())

        /** The [boxes], each grown by [halo] pixels on every side, clipped to the capture. */
        fun of(
            width: Int,
            height: Int,
            boxes: List<LookBox>,
            halo: Int = 0,
        ): PixelMask {
            val bits = BitSet()
            boxes.forEach { fill(bits, width, height, it.x - halo, it.y - halo, it.width + 2 * halo, it.height + 2 * halo) }
            return PixelMask(width, height, bits)
        }

        private fun fill(
            bits: BitSet,
            width: Int,
            height: Int,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ) {
            val x0 = x.coerceIn(0, width)
            val x1 = (x.toLong() + w).coerceIn(x0.toLong(), width.toLong()).toInt()
            val y0 = y.coerceIn(0, height)
            val y1 = (y.toLong() + h).coerceIn(y0.toLong(), height.toLong()).toInt()
            if (x0 == x1) return
            for (row in y0 until y1) bits.set(row * width + x0, row * width + x1)
        }
    }
}

/** A capture's ignored pixels by reason: each pixel goes to the first reason (in [IgnoredBy] order) that covers it. */
object IgnoredPixels {
    fun byReason(
        width: Int,
        height: Int,
        masks: List<LookMask>,
        halo: Int,
        moving: CellMask?,
        peers: CellMask?,
    ): Map<IgnoredBy, Long> {
        var taken = PixelMask.none(width, height)
        var counted = 0L
        val out = LinkedHashMap<IgnoredBy, Long>()
        IgnoredBy.entries.forEach { reason ->
            val next =
                when (reason) {
                    IgnoredBy.MOVING -> {
                        moving?.let(taken::with)
                    }

                    IgnoredBy.PEERS -> {
                        peers?.let(taken::with)
                    }

                    else -> {
                        val boxes = masks.filter { it.reason.name == reason.name }.map { it.box }
                        if (boxes.isEmpty()) null else taken.with(PixelMask.of(width, height, boxes, halo))
                    }
                } ?: return@forEach
            val total = next.count()
            if (total > counted) out[reason] = total - counted
            taken = next
            counted = total
        }
        return out
    }
}
