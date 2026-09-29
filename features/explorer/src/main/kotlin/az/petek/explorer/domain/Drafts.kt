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
 * Who is meant to see what an action creates (Faza 19). Objects a visitor sees (an article, a public post) are the
 * site's to show, so another tester opening one is no leak; a draft is never meant for anyone else, even on a site
 * whose published objects are public (the news card "a draft must not leak").
 */
object Drafts {
    /** "Draft" in the languages sites are written in; matched in the action's name and id. */
    private val WORDS =
        listOf("draft", "qaralama", "taslak", "черновик", "chernovik", "entwurf", "brouillon", "borrador", "rascunho", "bozza", "szkic")

    /** Whether [action] saves a draft rather than publishing. */
    fun saves(action: ActionModel): Boolean {
        val text = "${action.name} ${action.id}".lowercase()
        return WORDS.any { it in text }
    }

    /** Whether the anonymous visitor saw pages of [pattern]: the site shows those objects to everyone. */
    fun public(
        model: SiteModel,
        pattern: String,
    ): Boolean = model.pageByPattern(pattern)?.reachableBy?.contains(SiteModelAccumulator.ANONYMOUS) == true
}
