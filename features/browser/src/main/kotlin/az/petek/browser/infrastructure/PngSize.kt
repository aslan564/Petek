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

package az.petek.browser.infrastructure

/** A PNG's size in pixels. */
internal data class PngSize(
    val width: Int,
    val height: Int,
) {
    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val HEADER_CHUNK = "IHDR".toByteArray(Charsets.US_ASCII)
        private const val HEADER_TYPE_AT = 12
        private const val WIDTH_AT = 16
        private const val HEIGHT_AT = 20
        private const val HEADER_END = 24

        /**
         * The size written in [png]'s header (the IHDR chunk's first eight bytes, at 16–23), so a frame is measured without
         * decoding it; null when [png] is not a PNG.
         */
        fun of(png: ByteArray): PngSize? {
            if (png.size < HEADER_END) return null
            if (!png.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) return null
            if (!png.copyOfRange(HEADER_TYPE_AT, HEADER_TYPE_AT + HEADER_CHUNK.size).contentEquals(HEADER_CHUNK)) return null
            val width = bigEndian(png, WIDTH_AT)
            val height = bigEndian(png, HEIGHT_AT)
            return if (width > 0 && height > 0) PngSize(width, height) else null
        }

        private fun bigEndian(
            bytes: ByteArray,
            at: Int,
        ): Int = (0 until Int.SIZE_BYTES).fold(0) { value, i -> (value shl Byte.SIZE_BITS) or (bytes[at + i].toInt() and 0xFF) }
    }
}
