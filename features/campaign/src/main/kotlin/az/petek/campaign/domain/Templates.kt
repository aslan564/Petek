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
