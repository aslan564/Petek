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

package az.petek.faketarget.mail

/**
 * The subset of Mailpit's search syntax the fake supports: `to:`, `from:`, `subject:`, `is:read` / `is:unread` and free
 * words, each optionally quoted (`to:"a@b.c"`) or negated (`-word`, `!to:x`). Like Mailpit, every term is a
 * case-insensitive substring match and all terms must match. Unknown `key:value` terms are searched as free text.
 */
internal class MailSearchQuery private constructor(
    private val terms: List<Term>,
) {
    fun matches(mail: SentMail): Boolean = terms.all { it.matches(mail) }

    private enum class Field { TO, FROM, SUBJECT, IS, TEXT }

    private data class Term(
        val field: Field,
        val value: String,
        val negated: Boolean,
    ) {
        fun matches(mail: SentMail): Boolean = hit(mail) != negated

        private fun hit(mail: SentMail): Boolean =
            when (field) {
                Field.TO -> {
                    mail.to.any { it.address.contains(value, ignoreCase = true) || it.name.contains(value, ignoreCase = true) }
                }

                Field.FROM -> {
                    mail.from.address.contains(value, ignoreCase = true) || mail.from.name.contains(value, ignoreCase = true)
                }

                Field.SUBJECT -> {
                    mail.subject.contains(value, ignoreCase = true)
                }

                Field.IS -> {
                    when (value.lowercase()) {
                        "read" -> mail.read
                        "unread" -> !mail.read
                        else -> true
                    }
                }

                Field.TEXT -> {
                    sequenceOf(mail.subject, mail.text, mail.from.address, mail.from.name)
                        .plus(mail.to.flatMap { listOf(it.address, it.name) })
                        .any { it.contains(value, ignoreCase = true) }
                }
            }
    }

    companion object {
        private val FIELDS = mapOf("to" to Field.TO, "from" to Field.FROM, "subject" to Field.SUBJECT, "is" to Field.IS)

        fun parse(query: String): MailSearchQuery = MailSearchQuery(tokenize(query).mapNotNull(::toTerm))

        private fun toTerm(token: String): Term? {
            val negated = token.startsWith('-') || token.startsWith('!')
            val body = if (negated) token.drop(1) else token
            val colon = body.indexOf(':')
            val field = if (colon > 0) FIELDS[body.substring(0, colon).lowercase()] else null
            val value = unquote(if (field != null) body.substring(colon + 1) else body)
            return if (value.isEmpty()) null else Term(field ?: Field.TEXT, value, negated)
        }

        private fun unquote(value: String): String = value.replace("\"", "").trim()

        /** Splits on whitespace outside double quotes; quotes stay in the token and are removed by [unquote]. */
        private fun tokenize(query: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            for (c in query) {
                when {
                    c == '"' -> {
                        quoted = !quoted
                        current.append(c)
                    }

                    c.isWhitespace() && !quoted -> {
                        if (current.isNotEmpty()) tokens += current.toString()
                        current.clear()
                    }

                    else -> {
                        current.append(c)
                    }
                }
            }
            if (current.isNotEmpty()) tokens += current.toString()
            return tokens
        }
    }
}
