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

import java.net.URI
import java.net.URISyntaxException

/**
 * The credential headers the target's page itself sent with its own fetch and XHR calls (the owner's decision of
 * 2026-09-30, docs/PLAN.md): a site that keeps its access token in the page and sends it as `Authorization` (or a CSRF
 * header) answers the session's own probes ([PlaywrightBrowserSession.request], the `http_status` checks) only with it,
 * as it answers the page. Only the headers in [NAMES] of calls to the target's origin ([base]) or to the site's API on
 * its own host ([api]), the latest value of each, per origin; kept in memory for the session's life, sent back only to
 * the origin the page sent them to, never logged or written anywhere, and masked like a typed password in everything
 * the session reads back ([secrets], AGENTS.md rule 10). Pətək never reads the page's storage for them: only what the
 * page itself sent counts. Session thread only.
 */
internal class PageCredentials(
    base: URI,
    api: URI? = null,
) {
    private val origins = setOfNotNull(LocalStorageSeed.originOf(base), api?.let(LocalStorageSeed::originOf))
    private val sent = LinkedHashMap<String, LinkedHashMap<String, String>>()

    /** A request the page sent: its credential headers are kept when it is a fetch or XHR call to a known origin. */
    fun sent(
        url: String,
        resourceType: String,
        headers: Map<String, String>,
    ) {
        if (resourceType !in CALL_TYPES) return
        val origin = originOf(url)?.takeIf { it in origins } ?: return
        headers.forEach { (name, value) ->
            val key = name.lowercase()
            if (key in NAMES && value.isNotBlank()) sent.getOrPut(origin) { LinkedHashMap() }[key] = value
        }
    }

    /** The headers to send with a request to [url]: the ones the page sent [url]'s origin itself; none elsewhere. */
    fun headersFor(url: URI): Map<String, String> {
        val origin = originOf(url.toString()) ?: return emptyMap()
        return sent[origin]?.toMap().orEmpty()
    }

    /** What to mask in text read back: every value, and its credential without the scheme (`Bearer …`). */
    fun secrets(): Set<String> =
        sent.values
            .flatMap { it.values }
            .flatMap { value -> listOf(value, value.substringAfter(' ', "").trim()) }
            .filter { it.length >= MIN_SECRET_LENGTH }
            .toSet()

    private fun originOf(url: String): String? =
        try {
            URI(url).takeIf { it.scheme != null && it.host != null }?.let(LocalStorageSeed::originOf)
        } catch (_: URISyntaxException) {
            null
        }

    companion object {
        /** The headers a page sends a session's credentials in: an access token, or the CSRF token cookies need. */
        val NAMES = setOf("authorization", "x-auth-token", "x-access-token", "x-csrf-token", "x-xsrf-token", "x-csrftoken")

        /** Only calls the page's own script makes carry such headers; a navigation or an image never does. */
        private val CALL_TYPES = setOf("fetch", "xhr")

        /** A shorter value is masked only as the whole header, so a short token cannot shred unrelated text. */
        private const val MIN_SECRET_LENGTH = 8
    }
}
