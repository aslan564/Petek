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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageIoRasterCodecTest {
    private val codec = ImageIoRasterCodec()

    private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    @Test
    fun `a PNG decodes to the pixels it was encoded from`() {
        val opaque = Raster(4, 3, IntArray(12) { 0xFF000000.toInt() or (it * 0x0A1B2C) })
        val translucent = Raster(2, 2, intArrayOf(0x80FF0000.toInt(), 0xFF00FF00.toInt(), 0x00000000, 0x40102030))

        val opaqueBack = codec.decode(codec.encode(opaque))
        val translucentBack = codec.decode(codec.encode(translucent))

        (opaqueBack.width to opaqueBack.height) shouldBe (4 to 3)
        opaqueBack.argb.toList() shouldBe opaque.argb.toList()
        translucentBack.argb.toList() shouldBe translucent.argb.toList()
        String(codec.encode(opaque), 1, 3, Charsets.US_ASCII) shouldBe "PNG"
    }

    @Test
    fun `palette and grey PNGs decode to opaque ARGB`() {
        val palette = BufferedImage(3, 2, BufferedImage.TYPE_BYTE_INDEXED).apply { setRGB(0, 0, 0xFFFFFFFF.toInt()) }
        val grey =
            BufferedImage(2, 1, BufferedImage.TYPE_BYTE_GRAY).apply {
                raster.setSample(0, 0, 0, 0)
                raster.setSample(1, 0, 0, 255)
            }

        val fromPalette = codec.decode(png(palette))
        val fromGrey = codec.decode(png(grey))

        fromPalette.argb.all { it ushr 24 == 0xFF } shouldBe true
        fromPalette[0, 0] shouldBe 0xFFFFFFFF.toInt()
        fromGrey.argb.toList() shouldBe listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    }

    @Test
    fun `bytes that are no image are refused`() {
        shouldThrow<IllegalArgumentException> { codec.decode(byteArrayOf(1, 2, 3)) }
    }
}
