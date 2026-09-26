/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.domain

/**
 * Values available to `{placeholder}` templates in steps and assertions.
 * `{last_id}`, `{self.email}`, `{self.name}`, `{self.agent_id}`, `{self.department}`, `{self.role}`, `{event.<name>.id}`,
 * `{tester.<role>.<n>.name|email}`.
 */
data class TemplateContext(
    val lastId: String?,
    val self: Map<String, String>,
    val eventIds: Map<String, String>,
    /** Other testers by `<role>.<n>` (1-based, agent order): only [Placeholder.TESTER_FIELDS]. */
    val testers: Map<String, Map<String, String>> = emptyMap(),
)

/** Pure template rendering. Unknown or unresolvable placeholders fail loudly instead of producing wrong URLs. */
interface TemplateRenderer {
    fun render(
        template: String,
        context: TemplateContext,
    ): String

    /** Placeholder names used in [template], e.g. `last_id`, `self.email`. */
    fun placeholders(template: String): Set<String>
}

class TemplateException(
    message: String,
) : az.petek.core.error.PetekException(message)
