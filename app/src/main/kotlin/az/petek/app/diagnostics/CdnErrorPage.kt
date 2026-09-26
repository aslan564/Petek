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

package az.petek.app.diagnostics

/**
 * Recognises the pages a CDN or web firewall shows **instead of** the site: an error (the site's DNS or origin is
 * broken) or a block (a bot challenge, an access denial). Such an answer is not the site that was given, so a run or an
 * exploration must not start on it (AGENTS.md rule 12: "bloklanıbsa ... səbəb olduğu kimi bildirilir"). An ordinary 403
 * or 401 of the site itself (a login wall) is the site and is not recognised here.
 */
object CdnErrorPage {
    /** What the page is, e.g. `Cloudflare error 1000: DNS points to prohibited IP`, or null for the site's own answer. */
    fun of(answer: HttpCheck.Answered): String? {
        val server = answer.headers["server"].orEmpty().lowercase()
        val body = answer.bodyStart
        val title =
            TITLE
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.replace(WHITESPACE, " ")
        return when {
            answer.headers["cf-mitigated"].equals("challenge", ignoreCase = true) -> {
                "Cloudflare shows a bot challenge instead of the site, so automated browsers are blocked"
            }

            "cloudflare" in server && answer.status >= FIRST_ERROR && isCloudflarePage(body, title) -> {
                val code = CLOUDFLARE_CODE.find(body)?.groupValues?.get(1)
                val what =
                    title?.substringBefore(" | ")?.takeIf { it.isNotBlank() } ?: code?.let(CLOUDFLARE_MEANINGS::get) ?: "an error page"
                "Cloudflare " + (code?.let { "error $it: " } ?: "") + what
            }

            // What Cloudflare answers a client that is not a browser: a one-line `error code: 521`.
            "cloudflare" in server && answer.status >= FIRST_ERROR && CLOUDFLARE_PLAIN.matches(body.trim()) -> {
                val code = checkNotNull(CLOUDFLARE_PLAIN.matchEntire(body.trim())).groupValues[1]
                "Cloudflare error $code" + (CLOUDFLARE_MEANINGS[code]?.let { ": $it" } ?: "")
            }

            "cloudfront" in server && answer.status >= FIRST_ERROR && "The request could not be satisfied" in body -> {
                "CloudFront error: the request could not be satisfied"
            }

            "akamaighost" in server && "Access Denied" in body -> {
                "Akamai denies access (Access Denied)"
            }

            "Sucuri WebSite Firewall" in body -> {
                "the Sucuri firewall blocks access"
            }

            "Incapsula incident ID" in body || "_Incapsula_Resource" in body -> {
                "the Imperva (Incapsula) firewall blocks access"
            }

            else -> {
                null
            }
        }
    }

    private fun isCloudflarePage(
        body: String,
        title: String?,
    ): Boolean =
        "cf-error-details" in body ||
            "cf-wrapper" in body ||
            title?.let { t -> CLOUDFLARE_TITLES.any { it in t } } == true

    private const val FIRST_ERROR = 400
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val WHITESPACE = Regex("\\s+")
    private val CLOUDFLARE_CODE = Regex("Error\\s+(1\\d{3}|5\\d{2})")
    private val CLOUDFLARE_TITLES = listOf("| Cloudflare", "Attention Required!", "Just a moment...", "Access denied")
    private val CLOUDFLARE_PLAIN = Regex("error code: (\\d{3,4})", RegexOption.IGNORE_CASE)

    /** Cloudflare's own explanations of its common error codes (developers.cloudflare.com, "Troubleshooting"). */
    private val CLOUDFLARE_MEANINGS =
        mapOf(
            "520" to "the site's server answered with an unknown error",
            "521" to "the site's own server is down or refuses Cloudflare's connections",
            "522" to "Cloudflare's connection to the site's server timed out",
            "523" to "the site's server cannot be reached",
            "524" to "the site's server took too long to answer",
            "525" to "the TLS handshake between Cloudflare and the site's server failed",
            "526" to "the site's server has an invalid TLS certificate",
            "530" to "the site's server cannot be reached",
            "1000" to "DNS points to prohibited IP",
            "1001" to "DNS resolution error",
            "1003" to "direct IP access is not allowed",
            "1015" to "rate limited",
            "1016" to "the site's origin DNS name does not resolve",
            "1020" to "access denied by a firewall rule",
        )
}
