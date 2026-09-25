/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
