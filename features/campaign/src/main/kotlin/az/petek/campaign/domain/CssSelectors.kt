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

package az.petek.campaign.domain

/**
 * Selectors the page itself can read. Flows and assertions go through Playwright, which also understands its own
 * engines (`role=button[name="…"]`, `text=…`, `a >> nth=0`, `li:has-text("…")`); a look's masks are found in the page
 * with `document.querySelectorAll` ([VisualProfile]), which refuses all of these.
 */
object CssSelectors {
    /**
     * The part of [selector] only Playwright understands (an engine prefix such as `text=`, a chain `>>`, a pseudo-class
     * such as `:has-text(`), or null when it is plain CSS as far as these signs tell.
     */
    fun playwrightOnly(selector: String): String? {
        val trimmed = selector.trim()
        PREFIXES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }?.let { return it }
        return PARTS.firstOrNull { trimmed.contains(it, ignoreCase = true) }
    }

    /** Playwright's selector engines, and `//`, its short form of an XPath. */
    private val PREFIXES =
        listOf("text=", "xpath=", "css=", "id=", "role=", "data-testid=", "internal:", "nth=", "//")

    /** Playwright's chains and pseudo-classes. */
    private val PARTS =
        listOf(
            ">>",
            ":has-text(",
            ":text(",
            ":text-is(",
            ":text-matches(",
            ":visible",
            ":nth-match(",
            ":left-of(",
            ":right-of(",
            ":above(",
            ":below(",
            ":near(",
        )
}
