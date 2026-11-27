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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A box as `look-read.js` measured it, in fractional CSS pixels of the page. */
internal data class MeasuredBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** An area not to compare as measured; [LookReading.areas] fits it to a frame. */
internal data class MeasuredArea(
    val box: MeasuredBox,
    val reason: LookAreaReason,
    val source: String,
)

/** An element a mask could name, as measured; [LookReading.anchors] fits it to a frame. */
internal data class MeasuredAnchor(
    val selector: String,
    val box: MeasuredBox,
)

/** What `look-read.js` read with one load's final frame. */
internal class LookFacts(
    val path: String,
    val status: Int?,
    val pageHeight: Int,
    val userAgent: String,
    val fonts: List<String>,
    val areas: List<MeasuredArea>,
    val anchors: List<MeasuredAnchor>,
    val rejectedSelectors: List<String>,
)

/**
 * Reads what `look-read.js` returned ([facts]) and fits its boxes to a captured frame ([areas], [anchors]): clipped to the
 * frame, rounded outward to whole pixels, at most [MAX_AREAS] areas per frame. Anything malformed is left out.
 */
internal object LookReading {
    /** Areas per frame; past it, each reason's remaining areas become one box around them. */
    const val MAX_AREAS = 400

    /** Areas the page script lists one by one before it grows one box per reason around the rest. */
    const val SCRIPT_MAX_AREAS = 2_000
    const val MAX_ANCHORS = 300
    const val MAX_FONTS = 40

    /** Elements of one mask selector that are measured. */
    const val MAX_PER_SELECTOR = 50

    /** How the step's own mask selectors are named ([az.petek.browser.domain.LookSelector.source]: `look_mask:<key>`). */
    const val STEP_SOURCE_PREFIX = "look_mask:"

    private const val MERGED_SOURCES = 3

    /** The reason a mask selector's areas are given: the step's own, else the owner's profile. */
    fun selectorReason(source: String): LookAreaReason =
        if (source.startsWith(STEP_SOURCE_PREFIX)) LookAreaReason.STEP else LookAreaReason.PROFILE

    /** [redact] masks secrets in what the page tells (its path, the anchors' selectors); an anchor it would change is left out. */
    fun facts(
        raw: Map<*, *>,
        redact: (String) -> String,
    ): LookFacts =
        LookFacts(
            path = redact(raw["path"] as? String ?: "/"),
            status = (raw["status"] as? Number)?.toInt()?.takeIf { it > 0 },
            pageHeight = (raw["pageHeight"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
            userAgent = raw["userAgent"] as? String ?: "",
            fonts =
                (raw["fonts"] as? List<*>)
                    .orEmpty()
                    .filterIsInstance<String>()
                    .distinct()
                    .sorted()
                    .take(MAX_FONTS),
            areas =
                (raw["areas"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { area ->
                    val reason = LookAreaReason.entries.firstOrNull { it.name == area["r"] } ?: return@mapNotNull null
                    box(area)?.let { MeasuredArea(it, reason, area["s"] as? String ?: reason.name.lowercase()) }
                },
            anchors =
                (raw["anchors"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { anchor ->
                    val selector = (anchor["selector"] as? String)?.takeIf { redact(it) == it } ?: return@mapNotNull null
                    box(anchor)?.let { MeasuredAnchor(selector, it) }
                },
            rejectedSelectors = (raw["rejected"] as? List<*>).orEmpty().filterIsInstance<String>().distinct(),
        )

    /** [measured] areas within a frame of [width] × [height]: clipped, rounded outward, at most [max]. */
    fun areas(
        measured: List<MeasuredArea>,
        width: Int,
        height: Int,
        max: Int = MAX_AREAS,
    ): List<LookArea> {
        val fitted =
            measured.mapNotNull { area ->
                fit(area.box, width, height)?.let { (x, y, w, h) -> LookArea(x, y, w, h, area.reason, area.source) }
            }
        if (fitted.size <= max) return fitted
        // Each reason with areas past the cap keeps one box around them, and the boxes count against the cap.
        var kept = max
        while (kept > 0 && kept + fitted.drop(kept).distinctBy { it.reason }.size > max) kept--
        val rest =
            fitted.drop(kept).groupBy { it.reason }.map { (reason, group) ->
                val left = group.minOf { it.x }
                val top = group.minOf { it.y }
                val right = group.maxOf { it.x + it.width }
                val bottom = group.maxOf { it.y + it.height }
                LookArea(left, top, right - left, bottom - top, reason, sourcesOf(group))
            }
        return fitted.take(kept) + rest
    }

    /** The anchors of [facts] within a frame of [width] × [height]: clipped, rounded outward, at most [MAX_ANCHORS]. */
    fun anchors(
        facts: LookFacts,
        width: Int,
        height: Int,
    ): List<PageAnchor> =
        facts.anchors
            .mapNotNull { anchor -> fit(anchor.box, width, height)?.let { (x, y, w, h) -> PageAnchor(anchor.selector, x, y, w, h) } }
            .take(MAX_ANCHORS)

    private fun box(raw: Map<*, *>): MeasuredBox? {
        val values = listOf("x", "y", "w", "h").map { (raw[it] as? Number)?.toDouble() ?: return null }
        if (values.any { !it.isFinite() }) return null
        val (x, y, width, height) = values
        return MeasuredBox(x, y, width, height)
    }

    /** The whole pixels [box] touches within [width] × [height], as x, y, width and height; null when none. */
    private fun fit(
        box: MeasuredBox,
        width: Int,
        height: Int,
    ): List<Int>? {
        if (box.width <= 0 || box.height <= 0) return null
        val left = max(0.0, floor(box.x)).toInt()
        val top = max(0.0, floor(box.y)).toInt()
        val right = min(width.toDouble(), ceil(box.x + box.width)).toInt()
        val bottom = min(height.toDouble(), ceil(box.y + box.height)).toInt()
        return if (right > left && bottom > top) listOf(left, top, right - left, bottom - top) else null
    }

    private fun sourcesOf(group: List<LookArea>): String {
        val sources = group.map { it.source }.distinct()
        val named = sources.take(MERGED_SOURCES).joinToString(",")
        return if (sources.size > MERGED_SOURCES) "$named,…" else named
    }
}
