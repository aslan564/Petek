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

/**
 * A decoded image: [width] × [height] pixels as non-premultiplied `0xAARRGGBB` ints, row by row. A page look's frame
 * is one CSS pixel per image pixel, so its coordinates are the page's own (docs/adr/0014).
 */
class Raster(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width >= 0 && height >= 0) { "size must not be negative, was ${width}x$height" }
        require(argb.size.toLong() == width.toLong() * height) { "argb must hold ${width}x$height pixels, held ${argb.size}" }
    }

    operator fun get(
        x: Int,
        y: Int,
    ): Int = argb[y * width + x]

    /** The part of this raster inside [box], clipped to its edges (empty when they do not meet). */
    fun crop(box: LookBox): Raster {
        val x0 = box.x.coerceIn(0, width)
        val y0 = box.y.coerceIn(0, height)
        val x1 = (box.x.toLong() + box.width).coerceIn(x0.toLong(), width.toLong()).toInt()
        val y1 = (box.y.toLong() + box.height).coerceIn(y0.toLong(), height.toLong()).toInt()
        val w = x1 - x0
        val h = y1 - y0
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(argb, (y0 + y) * width + x0, out, y * w, w)
        return Raster(w, h, out)
    }

    /** The top [rows] rows of this raster (all of them when it is not taller). */
    fun top(rows: Int): Raster = if (rows >= height) this else crop(LookBox(0, 0, width, rows.coerceAtLeast(0)))
}

/**
 * Turns image files into [Raster]s and back (the reporting infrastructure's `ImageIoRasterCodec`). Blocking: callers
 * run it on a CPU dispatcher. [decode] throws when the bytes are not an image it can read.
 */
interface RasterCodec {
    fun decode(png: ByteArray): Raster

    fun encode(raster: Raster): ByteArray
}
