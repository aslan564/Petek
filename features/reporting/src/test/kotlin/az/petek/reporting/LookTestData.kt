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

package az.petek.reporting

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.LookAnchor
import az.petek.evidence.domain.LookBox
import az.petek.evidence.domain.LookFrame
import az.petek.evidence.domain.LookFrameKind
import az.petek.evidence.domain.LookMask
import az.petek.evidence.domain.LookMaskReason
import az.petek.evidence.domain.PageLookRecord
import az.petek.reporting.domain.visual.Raster
import az.petek.reporting.domain.visual.RasterCodec
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Page looks and synthetic captures for the visual comparison's tests: no browser, no image library. */
object LookTestData {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val RED = 0xFFE01010.toInt()
    const val BLUE = 0xFF1030E0.toInt()
    const val RENDERER = "chromium 141.0; Linux x86_64; headless"

    fun look(
        agent: String,
        runId: RunId = ReportTestData.RUN_ID,
        step: String = "public-look",
        page: String = "/",
        device: String? = "phone",
        frames: List<LookFrame> = listOf(frame("art_${runId}_$agent")),
        status: Int? = 200,
        landedPath: String = page,
        renderer: String = RENDERER,
        viewport: Pair<Int, Int> = 375 to 812,
        pageHeight: Int = 2_000,
        maxHeight: Int = 4_000,
        testers: Int = 4,
        settled: Boolean = true,
        fonts: List<String> = listOf("Inter 400 normal"),
        anchors: List<LookAnchor> = emptyList(),
        recordedAtMs: Long = 0,
    ) = PageLookRecord(
        runId = runId,
        stepId = StepId("stp_look_$agent"),
        agentId = AgentId(agent),
        scenarioStep = step,
        page = page,
        device = device,
        landedPath = landedPath,
        status = status,
        viewportWidth = viewport.first,
        viewportHeight = viewport.second,
        pageHeight = pageHeight,
        maxHeight = maxHeight,
        testers = testers,
        renderer = renderer,
        settled = settled,
        unsettled = if (settled) emptyList() else listOf("network"),
        fonts = fonts,
        frames = frames,
        anchors = anchors,
        recordedAt = ReportTestData.START.plusMillis(recordedAtMs),
    )

    fun frame(
        artifactId: String,
        kind: LookFrameKind = LookFrameKind.MAIN,
        width: Int = 375,
        height: Int = 2_000,
        masks: List<LookMask> = emptyList(),
    ) = LookFrame(ArtifactId(artifactId), kind, width, height, masks)

    fun mask(
        box: LookBox,
        reason: LookMaskReason = LookMaskReason.PROFILE,
        source: String = "visual.clock",
    ) = LookMask(box, reason, source)

    fun blank(
        width: Int,
        height: Int,
        color: Int = WHITE,
    ) = Raster(width, height, IntArray(width * height) { color })

    /**
     * A page whose every row is unique (a marker in the first three columns, made from [seed] and the row) and white
     * otherwise, so rows anchor the alignment and the rest can be painted as the test needs.
     */
    fun page(
        width: Int,
        height: Int,
        seed: Int = 1,
    ): Raster {
        val argb = IntArray(width * height) { WHITE }
        for (y in 0 until height) {
            val marker = 0xFF000000.toInt() or ((y and 0xff) shl 16) or (((y shr 8) and 0xff) shl 8) or (seed and 0xff)
            for (x in 0 until minOf(3, width)) argb[y * width + x] = marker
        }
        return Raster(width, height, argb)
    }

    /** A copy of [raster] with the box painted [color]. */
    fun Raster.painted(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
    ): Raster {
        val out = argb.copyOf()
        for (row in y until minOf(y + height, this.height)) {
            for (col in x until minOf(x + width, this.width)) out[row * this.width + col] = color
        }
        return Raster(this.width, this.height, out)
    }

    /** Rows of [raster] with [rows] inserted at [at]. */
    fun Raster.inserted(
        at: Int,
        rows: Raster,
    ): Raster {
        require(rows.width == width)
        val out = IntArray(width * (height + rows.height))
        System.arraycopy(argb, 0, out, 0, at * width)
        System.arraycopy(rows.argb, 0, out, at * width, rows.argb.size)
        System.arraycopy(argb, at * width, out, (at + rows.height) * width, (height - at) * width)
        return Raster(width, height + rows.height, out)
    }

    /** Rows of [raster] without `[from, from + count)`. */
    fun Raster.removed(
        from: Int,
        count: Int,
    ): Raster {
        val out = IntArray(width * (height - count))
        System.arraycopy(argb, 0, out, 0, from * width)
        System.arraycopy(argb, (from + count) * width, out, from * width, (height - from - count) * width)
        return Raster(width, height - count, out)
    }

    /**
     * A codec of raw ARGB (`width`, `height`, then the pixels), so the comparison's tests need no image library; it
     * counts what it decodes.
     */
    class RawCodec : RasterCodec {
        val decodes = AtomicInteger()

        override fun decode(png: ByteArray): Raster {
            decodes.incrementAndGet()
            val buffer = ByteBuffer.wrap(png)
            require(png.size >= 8) { "not a raw raster" }
            val width = buffer.int
            val height = buffer.int
            require(png.size == 8 + width * height * 4) { "not a raw raster" }
            return Raster(width, height, IntArray(width * height) { buffer.int })
        }

        override fun encode(raster: Raster): ByteArray =
            ByteBuffer
                .allocate(8 + raster.argb.size * 4)
                .putInt(raster.width)
                .putInt(raster.height)
                .apply { raster.argb.forEach(::putInt) }
                .array()
    }
}
