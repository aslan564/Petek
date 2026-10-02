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

package az.petek.app.runs

import az.petek.reporting.domain.visual.BandKind
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.LookReason
import java.util.Locale

/** A compared page look as one line of `petek compare`'s own output, in English (docs/adr/0014). */
internal object LookSummaries {
    /** `/ (phone, public-look): 2 regions, 1.4% of the page; 120 px inserted`. */
    fun changed(look: LookComparison): String {
        val parts =
            buildList {
                val regions = look.countedRegions.size
                if (regions > 0) add("$regions region(s), ${percent(look.changedShare)} of the page")
                look.countedBands.groupBy { it.kind }.forEach { (kind, bands) ->
                    val rows = bands.sumOf { it.height }
                    add("$rows px " + if (kind == BandKind.INSERTED) "inserted" else "removed")
                }
                look.facts.forEach { add("${it.kind.name.lowercase()} ${it.before} → ${it.after}") }
            }
        return title(look) + ": " + parts.ifEmpty { listOf("changed") }.joinToString(", ")
    }

    /** `/admin (desktop, public-look): browser or system differs`. */
    fun notComparable(look: LookComparison): String = title(look) + ": " + (look.reason?.let(::reason) ?: "not comparable")

    fun title(look: LookComparison): String =
        look.key.page + " (" + listOfNotNull(look.key.device, look.key.scenarioStep).joinToString(", ") + ")"

    fun reason(reason: LookReason): String =
        when (reason) {
            LookReason.MISSING_BEFORE -> "the baseline took no look of this page on this screen"
            LookReason.MISSING_NOW -> "this run took no look of this page on this screen"
            LookReason.SURROUNDINGS -> "the site answered 429/503: the surroundings, not the site"
            LookReason.OTHER_BROWSER -> "browser or system differs; take a new baseline on this machine"
            LookReason.OTHER_SCREEN -> "screen size differs"
            LookReason.KEPT_MOVING -> "the page kept changing by itself; mask the moving part (target_profile.visual.mask)"
            LookReason.MOSTLY_IGNORED -> "most of the page was not compared"
            LookReason.NOT_SETTLED -> "the page did not finish loading; the difference may be the loading"
            LookReason.NO_IMAGE -> "a frame is missing or unreadable"
            LookReason.EVIDENCE_ALTERED -> "a frame changed on disk: its sha256 does not match"
        }

    private fun percent(share: Double): String = String.format(Locale.ROOT, "%.1f%%", share * 100)
}
