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
 * How the explorer names the objects of a site (`tickets`, `announcements`): what a form creates, what a page lists or
 * shows. Drafts name their events by it, and a test API serves the objects under it (`/test/<resource>/...`,
 * docs/TARGET_CONTRACT.md), so the trial touch and the draft ask the test API about the same name.
 */
object Resources {
    /** Path segments that name what is done, not what it is done to (`/tickets/new`, `/tickets/{id}/edit`). */
    val VERB_SEGMENTS: Set<String> = setOf("new", "create", "add", "edit", "update", "yeni", "yarat", "elave", "redakte")

    /** What [action] creates: named by its form's action path when it has one, else by its page. */
    fun created(
        action: ActionModel,
        page: PageModel,
    ): String = collectionOf(action.httpPath ?: page.urlPattern)

    /** The collection a pattern lists or posts to: its last segment that is neither an id nor a verb. */
    fun collectionOf(pattern: String): String =
        pattern
            .split('/')
            .lastOrNull { it.isNotEmpty() && it != UrlPatterns.ID && Keywords.fold(it) !in VERB_SEGMENTS }
            .let(::name)

    /** The object a pattern with ids shows or acts on: the segment right before its last id (`/tickets/{id}/approve`). */
    fun objectOf(pattern: String): String {
        val segments = pattern.split('/').filter { it.isNotEmpty() }
        val lastId = segments.lastIndexOf(UrlPatterns.ID)
        if (lastId < 0) return collectionOf(pattern)
        return name(segments.take(lastId).lastOrNull { it != UrlPatterns.ID })
    }

    private fun name(segment: String?): String = segment?.let { Slugs.of(it) }?.ifEmpty { null } ?: "items"
}
