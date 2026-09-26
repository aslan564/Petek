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

package az.petek.core.model

/**
 * The language the owner works in, which every text Pətək's AI writes for them follows (the owner's decision,
 * 2026-09-26): the explorer's page purposes, questions and ideas, the testers' summaries and reported problems, the
 * triage's rationale. [AUTO] means the language of the owner's own text (the instructions, the task, the scenario),
 * and the page's language when there is none; a named language (`English`, `Azərbaycan dili`, `Deutsch`) is used
 * whatever the owner wrote. Nothing is ever forced to Azerbaijani: an owner who drives Pətək in English through their
 * own AI gets English back.
 */
@JvmInline
value class WorkingLanguage(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "language must not be blank" }
    }

    val isAuto: Boolean get() = value.equals(AUTO_VALUE, ignoreCase = true)

    /**
     * The rule for a prompt: what to write in, with [what] naming the texts it applies to (e.g. "purpose and
     * unknowns"). One sentence, ready to join a list of rules.
     */
    fun rule(what: String): String =
        if (isAuto) {
            "Write $what in the language the owner's own text (instructions, task, scenario) is written in; when there " +
                "is none, in the language of the page. Never switch to another language on your own, and do not mistake " +
                "Azerbaijani for Turkish."
        } else {
            "Write $what in $value, whatever language the page or the owner's text uses."
        }

    override fun toString(): String = value

    companion object {
        const val AUTO_VALUE = "auto"
        val AUTO = WorkingLanguage(AUTO_VALUE)

        /** [AUTO] for `auto` (any case) or a blank value, otherwise the trimmed name as given. */
        fun of(value: String?): WorkingLanguage {
            val trimmed = value?.trim().orEmpty()
            return if (trimmed.isEmpty() || trimmed.equals(AUTO_VALUE, ignoreCase = true)) AUTO else WorkingLanguage(trimmed)
        }
    }
}
