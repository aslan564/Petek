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

package az.petek.agent.testing

import az.petek.browser.testing.FakeBrowserSession
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * A small website scripted on a [FakeBrowserSession], for flows that describe sites other than the contract: a page is
 * the set of selectors (and texts) shown at a path, navigation shows the page of its path, and [on] makes an action
 * (as the fake records it, e.g. `clickSelector #submit`) move the site to another page or change it otherwise.
 */
class ScriptedSite(
    val browser: FakeBrowserSession,
) {
    private class Page(
        val selectors: Set<String>,
        val texts: Map<String, String>,
        val visibleTexts: Set<String>,
    )

    private val pages = ConcurrentHashMap<String, Page>()
    private val reactions = ConcurrentHashMap<String, () -> Unit>()

    init {
        browser.onAction = { action ->
            val reaction = reactions[action]
            when {
                reaction != null -> reaction()
                action.startsWith(NAVIGATE) -> show(action.removePrefix(NAVIGATE))
            }
        }
    }

    /** The page shown at [path] (query and fragment are ignored when looking pages up). */
    fun page(
        path: String,
        vararg selectors: String,
        texts: Map<String, String> = emptyMap(),
        visibleTexts: Set<String> = emptySet(),
    ) {
        pages[path] = Page(selectors.toSet(), texts, visibleTexts)
    }

    /** What happens when [action] is performed, e.g. `on("clickSelector #submit") { show("/home") }`. */
    fun on(
        action: String,
        reaction: () -> Unit,
    ) {
        reactions[action] = reaction
    }

    /** Shows the page of [url]'s path at [url]; an unknown path shows an empty page. */
    fun show(url: String) {
        val page = pages[pathOf(url)]
        browser.url = url
        browser.visibleSelectors.clear()
        browser.visibleTexts.clear()
        browser.selectorTexts.clear()
        page ?: return
        browser.visibleSelectors += page.selectors
        browser.visibleTexts += page.visibleTexts
        browser.selectorTexts += page.texts
    }

    private fun pathOf(url: String): String = URI(url).path?.takeIf { it.isNotEmpty() } ?: url

    private companion object {
        const val NAVIGATE = "navigate "
    }
}
