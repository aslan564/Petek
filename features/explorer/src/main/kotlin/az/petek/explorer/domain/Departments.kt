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
 * The departments the explorer saw on the site (Faza 25.2): the options of a select field that names a department
 * (`department`, `şöbə`), in the order first seen, without placeholders ("Seçin", "--") and without names an actor
 * expression could not address. Empty when no such field was seen: a draft then assumes none of its own.
 */
object Departments {
    private val FIELD = Regex("(?i)(department|dept|şöbə|sobe|bölmə|bolme)")
    private val PLACEHOLDER = Regex("(?i)^(--.*|-+|seç.*|sec.*|select.*|choose.*|all|hamısı|hamisi)$")
    private val UNADDRESSABLE = setOf('[', ']', ',', '|', '=', '*', '{', '}')
    const val MAX = 5

    fun seen(model: SiteModel): List<String> =
        model.pages
            .asSequence()
            .flatMap { page -> page.forms.asSequence().flatMap { it.fields.asSequence() } }
            .filter { it.type == "select" && FIELD.containsMatchIn("${it.name} ${it.label} ${it.testId.orEmpty()}") }
            .flatMap { it.options.asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !PLACEHOLDER.matches(it) && it.none { c -> c in UNADDRESSABLE } }
            .distinctBy { it.lowercase() }
            .take(MAX)
            .toList()
}
