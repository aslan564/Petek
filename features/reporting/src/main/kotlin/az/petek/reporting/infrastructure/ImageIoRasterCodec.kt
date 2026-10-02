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

package az.petek.reporting.infrastructure

import az.petek.reporting.domain.visual.Raster
import az.petek.reporting.domain.visual.RasterCodec
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * PNG files to [Raster]s and back with the JDK alone (docs/adr/0014): `ImageIO` and the pixel arrays of a
 * `BufferedImage`. No `Graphics2D`, no fonts and no toolkit, so nothing starts the desktop toolkit and
 * `java.awt.headless` is left as the process set it. Streams are cached in memory, never in temporary files.
 *
 * Palette and grey images decode to ARGB like any other; an image whose pixels are all opaque is written without an
 * alpha channel.
 */
class ImageIoRasterCodec : RasterCodec {
    override fun decode(png: ByteArray): Raster {
        // ImageIO.read closes the stream itself once it has read an image; the memory cache needs nothing else.
        val image =
            ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(png)))
                ?: throw IllegalArgumentException("not an image this JDK can read")
        val width = image.width
        val height = image.height
        return Raster(width, height, image.getRGB(0, 0, width, height, null, 0, width))
    }

    override fun encode(raster: Raster): ByteArray {
        // An empty crop is written as one transparent pixel: an image needs a size.
        val source = if (raster.width == 0 || raster.height == 0) Raster(1, 1, IntArray(1)) else raster
        val opaque = source.argb.all { it ushr ALPHA_SHIFT == OPAQUE }
        val image = BufferedImage(source.width, source.height, if (opaque) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        System.arraycopy(source.argb, 0, pixels, 0, pixels.size)
        val bytes = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(bytes).use { stream ->
            check(ImageIO.write(image, "png", stream)) { "no PNG writer in this JDK" }
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val ALPHA_SHIFT = 24
        const val OPAQUE = 0xff
    }
}
