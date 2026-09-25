package az.petek.browser.infrastructure

/**
 * JavaScript the adapter runs in the page or in Playwright's bundled Node.js. The sources live as resources next
 * to this class so they stay readable (and lintable) as JavaScript rather than as escaped Kotlin strings.
 */
internal object BundledScripts {
    /** Page function numbering visible interactive elements; returns the shape [SnapshotParser] reads. */
    val pageIndexer: String = load("page-indexer.js")

    /** Page function returning `page.content()`-equivalent HTML with secret input values blanked. */
    val domSnapshot: String = load("dom-snapshot.js")

    /** Page function returning the current non-empty values of secret inputs, for redaction. */
    val secretValues: String = load("secret-values.js")

    /** Page predicate: is a text (`{text}`) or a CSS selector match (`{selector}`) visible? Polled in the page. */
    val visibilityProbe: String = load("visibility-probe.js")

    /** Page function telling whether a selector is plain CSS (as opposed to Playwright-only syntax). */
    val isCssSelector: String = load("is-css-selector.js")

    /** Element function resolving a `<select>` option by label, then value; see [PlaywrightBrowserSession.select]. */
    val resolveOption: String = load("resolve-option.js")

    /** Node.js program hosting the shared Chromium; see [PlaywrightDriver.browserServerCommand]. */
    val browserServer: String = load("browser-server.js")

    private fun load(name: String): String =
        requireNotNull(BundledScripts::class.java.getResource(name)) { "missing bundled script $name" }.readText()
}
