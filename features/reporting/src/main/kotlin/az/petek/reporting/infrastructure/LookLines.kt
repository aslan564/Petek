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

import az.petek.reporting.domain.RunComparison

/** A comparison's page looks as one line each, in the owner's words (the panel and MCP `compare_runs`; docs/adr/0014). */
object LookLines {
    /** `/qiymetler · telefon · public-look — dəyişib: 2 sahə (ekranın 1,4%-i)`. */
    fun changed(comparison: RunComparison): List<String> = comparison.changedLooks.map { "${LookText.title(it)} — ${LookText.summary(it)}" }

    /** `/admin · masaüstü · public-look: brauzer və ya sistem fərqlidir (…)`. */
    fun notComparable(comparison: RunComparison): List<String> =
        comparison.incomparableLooks.map { look -> LookText.title(look) + (LookText.reason(look)?.let { ": $it" } ?: "") }
}
