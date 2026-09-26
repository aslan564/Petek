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

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role

/**
 * Parses the actor grammar documented on [ActorExpression]. Role names and criterion keys are case-insensitive;
 * department names are kept as written (they are matched against `campaign.departments` by the validator).
 * Any syntax error throws [CampaignValidationException] quoting the raw text, with the given line.
 */
class DefaultActorExpressionParser : ActorExpressionParser {
    override fun parse(
        raw: String,
        line: Int?,
    ): ActorExpression = ActorExpression(parseSelectors(raw, line), raw.trim())

    override fun parseList(
        items: List<String>,
        line: Int?,
    ): ActorExpression {
        if (items.isEmpty()) throw invalid("[]", "the actor list is empty", line)
        val selectors = items.flatMap { parseSelectors(it, line) }
        return ActorExpression(selectors, items.joinToString(" | ") { it.trim() })
    }

    private fun parseSelectors(
        raw: String,
        line: Int?,
    ): List<ActorSelector> {
        if (raw.isBlank()) throw invalid(raw, "the actor is empty", line)
        return splitUnion(raw, line).map { parseSelector(it, raw, line) }
    }

    /** Splits on top-level `|`, rejecting `|` inside brackets and unbalanced brackets. */
    private fun splitUnion(
        raw: String,
        line: Int?,
    ): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (char in raw) {
            when (char) {
                '[' -> {
                    if (depth > 0) throw invalid(raw, "nested '[' is not allowed", line)
                    depth++
                }

                ']' -> {
                    if (depth == 0) throw invalid(raw, "']' without a matching '['", line)
                    depth--
                }

                '|' -> {
                    if (depth > 0) throw invalid(raw, "'|' is not allowed inside [...]", line)
                    parts += current.toString()
                    current.clear()
                    continue
                }
            }
            current.append(char)
        }
        if (depth > 0) throw invalid(raw, "'[' is not closed", line)
        parts += current.toString()
        return parts
    }

    private fun parseSelector(
        text: String,
        raw: String,
        line: Int?,
    ): ActorSelector {
        val selector = text.trim()
        if (selector.isEmpty()) throw invalid(raw, "empty selector around '|'", line)
        val open = selector.indexOf('[')
        val roleText = if (open < 0) selector else selector.substring(0, open).trim()
        if (roleText.isEmpty()) throw invalid(raw, "'$selector' does not start with a role ($ROLE_NAMES)", line)
        val role =
            Role.fromKey(roleText)
                ?: throw invalid(raw, "'$roleText' is not a role name (a campaign role such as $ROLE_NAMES)", line)
        if (open < 0) return ActorSelector(role)
        val close = selector.indexOf(']')
        if (close != selector.lastIndex) throw invalid(raw, "unexpected text after ']' in '$selector'", line)
        return parseFilter(role, selector.substring(open + 1, close).trim(), raw, line)
    }

    private fun parseFilter(
        role: Role,
        filter: String,
        raw: String,
        line: Int?,
    ): ActorSelector =
        when {
            filter.isEmpty() -> throw invalid(raw, "empty filter '[]' (use [*] for all)", line)
            filter == "*" -> ActorSelector(role)
            '=' !in filter && ',' !in filter -> ActorSelector(role, department = checkDepartment(filter, raw, line))
            else -> parseCriteria(role, filter, raw, line)
        }

    private fun parseCriteria(
        role: Role,
        filter: String,
        raw: String,
        line: Int?,
    ): ActorSelector {
        var department: String? = null
        var registration: RegistrationMode? = null
        var nth: Int? = null
        val seen = mutableSetOf<String>()
        for (criterion in filter.split(',')) {
            val text = criterion.trim()
            if (text.isEmpty()) throw invalid(raw, "empty criterion in '[$filter]'", line)
            val eq = text.indexOf('=')
            if (eq < 0) {
                throw invalid(raw, "'$text' is not a criterion (expected dept=, reg= or n=; use dept=$text for a department)", line)
            }
            val key = text.substring(0, eq).trim().lowercase()
            val value = text.substring(eq + 1).trim()
            if (!seen.add(key)) throw invalid(raw, "criterion '$key' is given twice", line)
            when (key) {
                "dept" -> department = checkDepartment(value, raw, line)
                "reg" -> registration = parseRegistration(value, raw, line)
                "n" -> nth = parseIndex(value, raw, line)
                else -> throw invalid(raw, "unknown criterion '$key' (expected dept, reg or n)", line)
            }
        }
        return ActorSelector(role, department, registration, nth)
    }

    private fun checkDepartment(
        value: String,
        raw: String,
        line: Int?,
    ): String {
        if (value.isEmpty()) throw invalid(raw, "dept needs a department name", line)
        if (value.any { it in RESERVED_CHARS }) throw invalid(raw, "department '$value' contains one of $RESERVED_CHARS", line)
        return value
    }

    private fun parseRegistration(
        value: String,
        raw: String,
        line: Int?,
    ): RegistrationMode =
        RegistrationMode.fromKey(value)?.takeIf { it != RegistrationMode.OWNER }
            ?: throw invalid(raw, "reg must be invite, company_code, self, login or guest, was '$value'", line)

    private fun parseIndex(
        value: String,
        raw: String,
        line: Int?,
    ): Int =
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: throw invalid(raw, "n must be a positive integer (1-based), was '$value'", line)

    private fun invalid(
        raw: String,
        reason: String,
        line: Int?,
    ) = CampaignValidationException(listOf(ValidationIssue(line, "invalid actor '${raw.trim()}': $reason")))

    companion object {
        private val ROLE_NAMES = Role.entries.joinToString(", ") { it.key }

        /** Characters of the grammar itself; a department containing one of them cannot be named in an expression. */
        val RESERVED_CHARS: List<Char> = listOf('[', ']', ',', '|', '=', '*')
    }
}
