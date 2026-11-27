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

import az.petek.evidence.domain.LookAnchor
import az.petek.evidence.domain.LookBox

/**
 * Selectors the owner could add to `target_profile.visual.mask` to stop comparing a changed area that is not the
 * site's fault (a feed, a counter): for each counted region, the smallest element a mask could name ([LookAnchor]: a
 * test id or a stable id) whose box contains it or covers at least [COVER] of it. Each is a YAML-quoted list item's
 * value (single quotes, a quote doubled). Only suggested in the report; Pətək never applies one by itself.
 */
object MaskSuggestions {
    const val COVER = 0.8

    fun of(
        regions: List<ChangedRegion>,
        anchors: List<LookAnchor>,
    ): List<String> =
        regions
            .filter { it.counted }
            .mapNotNull { region ->
                anchors
                    .filter { covered(region.box, it.box) >= COVER }
                    .minByOrNull { it.box.width.toLong() * it.box.height }
                    ?.selector
            }.distinct()
            .map(::quoted)

    /** A YAML single-quoted scalar. */
    fun quoted(selector: String): String = "'" + selector.replace("'", "''") + "'"

    /** The share of [region] inside [anchor]. */
    private fun covered(
        region: LookBox,
        anchor: LookBox,
    ): Double {
        val w = minOf(region.x + region.width, anchor.x + anchor.width) - maxOf(region.x, anchor.x)
        val h = minOf(region.y + region.height, anchor.y + anchor.height) - maxOf(region.y, anchor.y)
        val area = region.width.toLong() * region.height
        return if (w <= 0 || h <= 0 || area == 0L) 0.0 else w.toLong() * h / area.toDouble()
    }
}
