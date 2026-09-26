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

package az.petek.explorer.domain

/**
 * Builds the selectors the model stores for fields and actions, in order of stability: `data-testid`
 * (docs/TARGET_CONTRACT.md), element id, form field name, and last Playwright's role selector with the accessible name
 * (`role=button[name="Göndər"]`), which the browser adapter accepts wherever a selector is expected.
 */
object Selectors {
    private val TEST_ID_SELECTOR = Regex("""^\[data-testid="((?:[^"\\]|\\.)*)"]$""")

    fun testId(id: String): String = "[data-testid=\"${escape(id)}\"]"

    fun id(id: String): String = "[id=\"${escape(id)}\"]"

    fun name(
        tag: String,
        name: String,
    ): String = "$tag[name=\"${escape(name)}\"]"

    fun role(
        role: String,
        name: String,
    ): String = "role=$role[name=\"${escape(name)}\"]"

    /** The test id a selector made by [testId] addresses, or null for any other selector. */
    fun testIdOf(selector: String): String? =
        TEST_ID_SELECTOR
            .find(selector)
            ?.groupValues
            ?.get(1)
            ?.let(::unescape)

    /** Escapes a value for a double-quoted CSS attribute or role-selector value. */
    fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String): String = value.replace("\\\"", "\"").replace("\\\\", "\\")
}
