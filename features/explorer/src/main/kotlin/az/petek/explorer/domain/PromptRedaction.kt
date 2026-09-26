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

import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot

/**
 * Removes secrets from what the explorer shows the LLM (CLAUDE.md rule 10). The browser already masks password
 * values; on top of that the page address is replaced by its pattern (paths and query strings carry invitation and
 * reset tokens), values of secret-looking fields (password, token, code, PIN, card) are masked, and long random tokens
 * and URL query strings in any text are replaced.
 */
object PromptRedaction {
    const val MASK = "******"
    const val REDACTED = "[redacted]"

    private val SECRET_WORDS =
        setOf("password", "passwd", "parol", "secret", "token", "otp", "code", "kod", "pin", "cvv", "cvc", "card", "iban")
    private val TOKEN =
        Regex("(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]*[0-9])(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}(?![A-Za-z0-9_-])")
    private val URL_QUERY = Regex("(https?://[^\\s?#]+)\\?[^\\s#]*")

    /** [snapshot] as the prompt may show it: [urlPattern] instead of the address, secrets masked, tokens removed. */
    fun snapshot(
        snapshot: PageSnapshot,
        urlPattern: String,
    ): PageSnapshot =
        snapshot.copy(
            url = urlPattern,
            title = scrub(snapshot.title),
            elements = snapshot.elements.map(::element),
            visibleText = scrub(snapshot.visibleText),
        )

    /** Replaces URL query strings and long random tokens in free text. */
    fun scrub(text: String): String = TOKEN.replace(URL_QUERY.replace(text, "$1?$REDACTED"), REDACTED)

    /** True for fields whose value must never reach the LLM. */
    fun isSecretField(element: PageElement): Boolean =
        element.value == MASK ||
            Keywords.words(listOfNotNull(element.testId, element.name).joinToString(" ")).any { word ->
                SECRET_WORDS.any { secret -> word == secret || word.startsWith(secret) }
            }

    private fun element(element: PageElement): PageElement =
        element.copy(
            name = scrub(element.name),
            value = element.value?.let { value -> if (value.isNotEmpty() && isSecretField(element)) MASK else scrub(value) },
        )
}
