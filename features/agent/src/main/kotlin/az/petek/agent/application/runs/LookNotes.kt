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

package az.petek.agent.application.runs

import az.petek.browser.domain.LookShotKind
import az.petek.browser.domain.PageLook

/**
 * The detail of a look's sub-action, e.g. `steady; 375x2310 of a 2310 px page on a 375x812 screen; masked: 2 run text,
 * 1 time text`, or `kept moving within the load, changed on reload; …; not settled: network, images; mask selector
 * rejected: .x[`. Masks are counted by reason only: a run text's value is never written.
 */
internal object LookNotes {
    fun of(
        look: PageLook,
        page: String,
    ): String {
        val kinds = look.shots.map { it.kind }.toSet()
        val motion =
            listOfNotNull(
                "kept moving within the load".takeIf { LookShotKind.MOVED in kinds },
                "changed on reload".takeIf { LookShotKind.RELOADED in kinds },
            ).ifEmpty { listOf("steady") }
        val main = look.shots.firstOrNull { it.kind == LookShotKind.MAIN } ?: look.shots.first()
        val notes = mutableListOf(motion.joinToString(", "))
        notes += "${main.width}x${main.height} of a ${look.pageHeight} px page on a ${look.viewport.width}x${look.viewport.height} screen"
        if (look.landedPath.trimEnd('/') != page.substringBefore('?').trimEnd('/')) notes += "landed on ${look.landedPath}"
        look.status?.takeIf { it >= ERROR_STATUS }?.let { notes += "answered $it" }
        val masked = main.areas.groupingBy { it.reason }.eachCount()
        if (masked.isNotEmpty()) {
            notes += "masked: " +
                masked.entries
                    .sortedBy { it.key.ordinal }
                    .joinToString(", ") { (reason, count) -> "$count ${reason.name.lowercase().replace('_', ' ')}" }
        }
        if (!look.settled) notes += "not settled: " + look.unsettled.joinToString(", ").ifEmpty { "within its time" }
        if (look.rejectedSelectors.isNotEmpty()) notes += "mask selector rejected: " + look.rejectedSelectors.joinToString(", ")
        return notes.joinToString("; ")
    }

    private const val ERROR_STATUS = 400
}
