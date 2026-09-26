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

package az.petek.verification.testing

import az.petek.campaign.domain.TemplateContext
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer

/**
 * Minimal stand-in for the campaign renderer (implemented elsewhere): resolves `{last_id}`, `{self.<key>}` and
 * `{event.<name>.id}`, and fails loudly on anything unknown, like the real contract requires.
 */
class FakeTemplateRenderer : TemplateRenderer {
    private val placeholder = Regex("""\{([a-z_]+(?:\.[a-z0-9_]+)*)}""")

    override fun render(
        template: String,
        context: TemplateContext,
    ): String =
        placeholder.replace(template) { match ->
            val name = match.groupValues[1]
            resolve(name, context) ?: throw TemplateException("Unknown or unresolved placeholder {$name}")
        }

    override fun placeholders(template: String): Set<String> = placeholder.findAll(template).map { it.groupValues[1] }.toSet()

    private fun resolve(
        name: String,
        context: TemplateContext,
    ): String? =
        when {
            name == "last_id" -> {
                context.lastId
            }

            name.startsWith("self.") -> {
                context.self[name.removePrefix("self.")]
            }

            name.startsWith("event.") && name.endsWith(".id") -> {
                context.eventIds[name.removePrefix("event.").removeSuffix(".id")]
            }

            else -> {
                null
            }
        }
}
