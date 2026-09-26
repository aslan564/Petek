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

package az.petek.agent.application

import az.petek.agent.domain.AgentRuntime
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** What a password is replaced with in any text that leaves the harness (prompts, evidence, summaries). */
internal const val PASSWORD_PLACEHOLDER = "{self.password}"

/**
 * Removes this agent's password from [text] (AGENTS.md rule 10). Applied to everything that may echo page
 * content or error messages back to the LLM or into evidence: a password field's value in a snapshot, an adapter
 * error that quotes the typed text, a `read_text` result, or a page URL after a form was submitted with GET (where
 * the password appears URL-encoded, e.g. `!` as `%21`).
 */
internal fun AgentRuntime.redact(text: String): String {
    val password = identity.password.reveal()
    if (password.isBlank()) return text
    return secretForms(password).fold(text) { redacted, form -> redacted.replace(form, PASSWORD_PLACEHOLDER) }
}

/** The password as typed and as it appears in a query string (`+` or `%20` for spaces). */
private fun secretForms(password: String): List<String> {
    val encoded = URLEncoder.encode(password, StandardCharsets.UTF_8)
    return listOf(password, encoded, encoded.replace("+", "%20")).distinct()
}

/** Shortens [text] for prompts and evidence details without cutting it silently. */
internal fun String.clip(maxChars: Int): String = if (length <= maxChars) this else take(maxChars) + "… (${length - maxChars} more chars)"
