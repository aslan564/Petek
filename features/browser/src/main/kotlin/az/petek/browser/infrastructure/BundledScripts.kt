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

package az.petek.browser.infrastructure

/**
 * JavaScript the adapter runs in the page or in Playwright's bundled Node.js. The sources live as resources next
 * to this class so they stay readable (and lintable) as JavaScript rather than as escaped Kotlin strings.
 */
internal object BundledScripts {
    /** Page function numbering visible interactive elements; returns the shape [SnapshotParser] reads. */
    val pageIndexer: String = load("page-indexer.js")

    /** Page function returning what a visitor can check without acting ([az.petek.browser.domain.PageFacts]). */
    val pageFacts: String = load("page-facts.js")

    /** Page function returning how fast the current page became usable ([az.petek.browser.domain.PageTiming]). */
    val pageTiming: String = load("page-timing.js")

    /** Page function returning `page.content()`-equivalent HTML with secret input values blanked. */
    val domSnapshot: String = load("dom-snapshot.js")

    /** Page function returning the current non-empty values of secret inputs, for redaction. */
    val secretValues: String = load("secret-values.js")

    /** Element predicate: is this a secret (password) field? See [PlaywrightBrowserSession.fill]. */
    val isSecretField: String = load("is-secret-field.js")

    /** Page predicate: is a text (`{text}`) or a CSS selector match (`{selector}`) visible? Polled in the page. */
    val visibilityProbe: String = load("visibility-probe.js")

    /**
     * Page function starting a text watch (`{key, text, gapMs, pollMs, maxMs}` -> `{before, armedAt}`); it matches text
     * with [visibilityProbe], embedded into it here. See [PlaywrightBrowserSession.watchText].
     */
    val textWatch: String = load("text-watch.js").replace(PROBE_PLACEHOLDER, withoutLeadingComments(visibilityProbe))

    /** Page function ending a text watch (`{key}` -> `{before, armedAt, seenAt}` or null). */
    val textWatchRead: String = load("text-watch-read.js")

    /** Page function telling whether a selector is plain CSS (as opposed to Playwright-only syntax). */
    val isCssSelector: String = load("is-css-selector.js")

    /** Element function resolving a `<select>` option by label, then value; see [PlaywrightBrowserSession.select]. */
    val resolveOption: String = load("resolve-option.js")

    /**
     * Page function bringing the page to rest before a look (`{settleMs, maxHeight, fontsMs, quietMs, stepMs}` ->
     * `{fontsReady, pendingImages, quiet, pageHeight, width, height}`); see [PlaywrightBrowserSession.look].
     */
    val lookSettle: String = load("look-settle.js")

    /** Page function reading a look's facts, areas and anchors after its final frame; [LookReading] reads the result. */
    val lookRead: String = load("look-read.js")

    /** Node.js program hosting the shared Chromium; see [PlaywrightDriver.browserServerCommand]. */
    val browserServer: String = load("browser-server.js")

    private fun load(name: String): String =
        requireNotNull(BundledScripts::class.java.getResource(name)) { "missing bundled script $name" }.readText()

    /** Where text-watch.js takes the visibility probe. */
    private const val PROBE_PLACEHOLDER = "__VISIBILITY_PROBE__"

    /** [script] from its first code on: without the license header and the description, to embed it in another script. */
    private fun withoutLeadingComments(script: String): String {
        var rest = script.trimStart()
        while (true) {
            rest =
                when {
                    rest.startsWith("/*") -> rest.substringAfter("*/").trimStart()
                    rest.startsWith("//") -> rest.substringAfter('\n', "").trimStart()
                    else -> return rest.trimEnd()
                }
        }
    }
}
