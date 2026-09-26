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

package az.petek.explorer.application

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.PageHealth
import az.petek.core.time.HarnessTimestamp
import az.petek.explorer.domain.SiteOrigin
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlin.time.Duration

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
        // The address that was checked is exactly the one passed on.
        delegate.navigate(requireOnOrigin(pathOrUrl))
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult {
        if (!method.equals("GET", ignoreCase = true) || body != null) refuse("$method request")
        return delegate.request("GET", requireOnOrigin(path), null)
    }

    // Reading what the page reported and measuring it at a phone's width are looks, not actions.
    override suspend fun health(
        since: HarnessTimestamp,
        slowAfter: Duration,
    ): PageHealth = delegate.health(since, slowAfter)

    override suspend fun horizontalOverflow(
        width: Int,
        height: Int,
    ): Int? = delegate.horizontalOverflow(width, height)

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

    /** [pathOrUrl] trimmed when it is an address on [origin] (absolute, or a path starting with one `/`); else refused. */
    private fun requireOnOrigin(pathOrUrl: String): String {
        val address = pathOrUrl.trim()
        val uri =
            try {
                URI(address)
            } catch (_: URISyntaxException) {
                refuse("opening an unparsable address")
            }
        val onOrigin =
            if (uri.isAbsolute) {
                origin.contains(uri)
            } else {
                uri.scheme == null && uri.rawAuthority == null && address.startsWith("/") && !address.startsWith("//")
            }
        if (!onOrigin) refuse("leaving the target origin")
        return address
    }

    private fun refuse(what: String): Nothing = throw IllegalStateException("The explorer is read-only here: $what is not allowed")
}
