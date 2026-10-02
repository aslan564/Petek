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

import az.petek.browser.domain.LookArea
import az.petek.browser.domain.LookAreaReason
import az.petek.browser.domain.PageAnchor
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class LookReadingTest {
    private fun area(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        reason: LookAreaReason,
        source: String,
    ) = MeasuredArea(MeasuredBox(x, y, width, height), reason, source)

    @Test
    fun `areas are clipped to the capture, rounded outward and capped at 400 with the rest merged per reason`() {
        val measured =
            listOf(
                area(10.4, 20.6, 30.2, 10.1, LookAreaReason.PROFILE, "visual.banner"),
                area(-5.0, 790.5, 20.0, 30.0, LookAreaReason.EMBED, "iframe"),
                area(5.0, 900.0, 10.0, 10.0, LookAreaReason.TIME_TEXT, "date"),
                area(1_270.0, 0.0, 0.0, 10.0, LookAreaReason.MARKUP, "data-petek-mask"),
            )

        LookReading.areas(measured, width = 1_280, height = 800) shouldBe
            listOf(
                LookArea(10, 20, 31, 11, LookAreaReason.PROFILE, "visual.banner"),
                LookArea(0, 790, 15, 10, LookAreaReason.EMBED, "iframe"),
            )

        val names = (0 until 450).map { row -> area(0.0, row.toDouble(), 10.0, 1.0, LookAreaReason.RUN_TEXT, "tester_name") }
        val times = (0 until 5).map { i -> area(100.0 + i, 500.0, 1.0, 1.0, LookAreaReason.TIME_TEXT, if (i == 0) "clock" else "date") }

        val capped = LookReading.areas(names + times, width = 1_280, height = 800)

        // The two merged boxes count against the cap: 398 areas as they are, then one box per reason around the rest.
        capped shouldHaveSize LookReading.MAX_AREAS
        capped.take(398) shouldBe (0 until 398).map { row -> LookArea(0, row, 10, 1, LookAreaReason.RUN_TEXT, "tester_name") }
        capped.drop(398) shouldBe
            listOf(
                LookArea(0, 398, 10, 52, LookAreaReason.RUN_TEXT, "tester_name"),
                LookArea(100, 500, 5, 1, LookAreaReason.TIME_TEXT, "clock,date"),
            )
    }

    @Test
    fun `a PNG header gives the frame's size`() {
        val png =
            ByteArrayOutputStream().use { out ->
                ImageIO.write(BufferedImage(1_366, 4_000, BufferedImage.TYPE_INT_RGB), "png", out)
                out.toByteArray()
            }

        PngSize.of(png) shouldBe PngSize(1_366, 4_000)
        PngSize.of(png.copyOf(20)).shouldBeNull()
        PngSize.of("GIF89a not a png at all".toByteArray()).shouldBeNull()
    }

    @Test
    fun `what the page told is read, its path redacted and an anchor naming a secret left out`() {
        val raw =
            mapOf(
                "path" to "/reset/s3cret",
                "status" to 404,
                "pageHeight" to 2_420.0,
                "userAgent" to "Mozilla/5.0 HeadlessChrome/153.0",
                "fonts" to listOf("Demo Sans 700 italic", "Demo Sans 400 normal", "Demo Sans 700 italic"),
                "areas" to
                    listOf(
                        mapOf("x" to 1, "y" to 2.5, "w" to 3, "h" to 4, "r" to "RUN_TEXT", "s" to "tester_name"),
                        mapOf("x" to 1, "y" to 2, "w" to 3, "h" to 4, "r" to "SOMETHING_ELSE", "s" to "x"),
                        mapOf("x" to "left", "y" to 2, "w" to 3, "h" to 4, "r" to "EMBED", "s" to "iframe"),
                    ),
                "anchors" to
                    listOf(
                        mapOf("selector" to "#promo", "x" to 220, "y" to 10.5, "w" to 100, "h" to 50),
                        mapOf("selector" to "[data-testid=\"s3cret\"]", "x" to 0, "y" to 0, "w" to 100, "h" to 50),
                    ),
                "rejected" to listOf("div[", "div["),
            )

        val facts = LookReading.facts(raw) { it.replace("s3cret", "***") }

        facts.path shouldBe "/reset/***"
        facts.status shouldBe 404
        facts.pageHeight shouldBe 2_420
        facts.userAgent shouldBe "Mozilla/5.0 HeadlessChrome/153.0"
        facts.fonts shouldBe listOf("Demo Sans 400 normal", "Demo Sans 700 italic")
        facts.rejectedSelectors shouldBe listOf("div[")
        LookReading.areas(facts.areas, 1_280, 800) shouldBe listOf(LookArea(1, 2, 3, 5, LookAreaReason.RUN_TEXT, "tester_name"))
        LookReading.anchors(facts, 1_280, 800) shouldBe listOf(PageAnchor("#promo", 220, 10, 100, 51))
    }

    @Test
    fun `the step's own mask selectors are named as the step's, every other one as the profile's`() {
        LookReading.selectorReason("look_mask:visual.clock") shouldBe LookAreaReason.STEP
        LookReading.selectorReason("visual.clock") shouldBe LookAreaReason.PROFILE
        LookReading.selectorReason(".news-ticker") shouldBe LookAreaReason.PROFILE
    }
}
