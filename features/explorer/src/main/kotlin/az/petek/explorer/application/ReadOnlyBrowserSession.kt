package az.petek.explorer.application

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.explorer.domain.SiteOrigin
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/**
 * The only view of a browser session the crawl passes get: it can look (navigate, snapshot, screenshot, GET) but
 * never act. Clicking, typing, selecting, non-GET requests, saving storage and closing throw
 * [IllegalStateException], and navigation or requests outside [origin] are refused the same way. This makes
 * "the explorer never submits anything outside TRIAL_TOUCH and never leaves the target" a property of the code, not
 * of the crawler's good behaviour.
 */
internal class ReadOnlyBrowserSession(
    private val delegate: BrowserSession,
    private val origin: SiteOrigin,
) : BrowserSession by delegate {
    override suspend fun navigate(pathOrUrl: String) {
        requireOnOrigin(pathOrUrl)
        delegate.navigate(pathOrUrl)
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult {
        if (!method.equals("GET", ignoreCase = true) || body != null) refuse("$method request")
        requireOnOrigin(path)
        return delegate.request("GET", path, null)
    }

    override suspend fun click(ref: Int) = refuse("click")

    override suspend fun fill(
        ref: Int,
        text: String,
        submit: Boolean,
    ) = refuse("typing")

    override suspend fun select(
        ref: Int,
        option: String,
    ) = refuse("selecting")

    override suspend fun clickSelector(selector: String) = refuse("click")

    override suspend fun fillSelector(
        selector: String,
        text: String,
    ) = refuse("typing")

    override suspend fun selectSelector(
        selector: String,
        option: String,
    ) = refuse("selecting")

    override suspend fun saveStorageState(path: Path) = refuse("saving the storage state")

    override suspend fun close() = refuse("closing a session it does not own")

    private fun requireOnOrigin(pathOrUrl: String) {
        val uri =
            try {
                URI(pathOrUrl.trim())
            } catch (_: URISyntaxException) {
                refuse("opening an unparsable address")
            }
        val onOrigin =
            if (uri.isAbsolute) {
                origin.contains(uri)
            } else {
                uri.scheme == null && uri.rawAuthority == null && pathOrUrl.trim().startsWith("/") && !pathOrUrl.trim().startsWith("//")
            }
        if (!onOrigin) refuse("leaving the target origin")
    }

    private fun refuse(what: String): Nothing = throw IllegalStateException("The explorer is read-only here: $what is not allowed")
}
