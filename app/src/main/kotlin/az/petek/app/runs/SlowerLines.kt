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

import az.petek.reporting.domain.PageComparison
import az.petek.reporting.domain.RunComparison
import java.util.Locale

/**
 * What got slower between two runs as one line each, for `petek compare` and the panel and MCP comparison: slower steps,
 * slower real-time delivery and worse pages. A page measure neither run reported is left out; one that only one run
 * reported shows a dash on the other side, never "null".
 */
internal class SlowerLines(
    private val load: String,
    private val decimalComma: Boolean,
) {
    fun of(comparison: RunComparison): List<String> =
        comparison.slowerSteps.map { "${it.scenarioStep} ${ms(it.beforeMs)} → ${ms(it.afterMs)}" } +
            comparison.slowerDeliveries.map { "${it.event} p95 ${ms(it.beforeP95Ms)} → ${ms(it.afterP95Ms)}" } +
            comparison.worsePages.map(::page)

    private fun page(page: PageComparison): String {
        val measures =
            listOfNotNull(
                pair(load, page.beforeLoadMs, page.afterLoadMs, ::ms),
                pair("LCP", page.beforePaintMs, page.afterPaintMs, ::ms),
                pair("CLS", page.beforeShift, page.afterShift, ::shift),
            )
        return page.page + (page.device?.let { " ($it)" } ?: "") + ": " + measures.joinToString(", ")
    }

    private fun <T : Any> pair(
        name: String,
        before: T?,
        after: T?,
        format: (T?) -> String,
    ): String? = if (before == null && after == null) null else "$name ${format(before)} → ${format(after)}"

    private fun ms(value: Long?): String = value?.let { "$it ms" } ?: NONE

    private fun shift(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.2f", it).let { text -> if (decimalComma) text.replace('.', ',') else text } } ?: NONE

    companion object {
        const val NONE = "—"

        /** For `petek compare`'s own output, in English. */
        val CLI = SlowerLines(load = "load", decimalComma = false)

        /** For the panel and MCP, said to the owner. */
        val PANEL = SlowerLines(load = "yüklənmə", decimalComma = true)
    }
}
