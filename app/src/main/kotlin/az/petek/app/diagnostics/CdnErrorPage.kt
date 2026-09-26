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
                val what = title?.substringBefore(" | ")?.takeIf { it.isNotBlank() } ?: "an error page"
                "Cloudflare " + (code?.let { "error $it: " } ?: "") + what
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
}
