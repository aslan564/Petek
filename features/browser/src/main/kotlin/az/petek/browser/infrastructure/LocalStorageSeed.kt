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

import az.petek.browser.domain.SessionOptions
import java.net.URI

/**
 * The context init script that seeds [SessionOptions.localStorage]: Playwright runs it in every new document before
 * the page's own scripts, so the site reads the values on its very first render (a first-visit dialog never opens).
 * It writes only when the document belongs to the target's origin, so a page on another host (an e-mail link to the
 * target's API server, say) never receives the target's keys. A browser that refuses storage access leaves the page
 * untouched instead of breaking it.
 */
internal object LocalStorageSeed {
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

    /** DEL and the line and paragraph separators, which JavaScript source must not contain raw inside a string. */
    private val ESCAPED_CODES = setOf(0x7f, 0x2028, 0x2029)

    fun script(options: SessionOptions): String? {
        if (options.localStorage.isEmpty()) return null
        val entries = options.localStorage.entries.joinToString(", ") { (key, value) -> "[${jsString(key)}, ${jsString(value)}]" }
        return "(() => { if (location.origin !== ${jsString(originOf(options.baseUrl))}) return; " +
            "try { for (const [key, value] of [$entries]) localStorage.setItem(key, value); } catch (e) {} })();"
    }

    /** `scheme://host[:port]` as `location.origin` spells it: lower case, default ports omitted. */
    fun originOf(url: URI): String {
        val scheme = url.scheme.lowercase()
        val host = url.host.lowercase()
        val port = url.port.takeUnless { it == -1 || it == DEFAULT_PORTS[scheme] }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /** A JavaScript string literal of [text]: JSON escaping, plus the line and paragraph separators. */
    fun jsString(text: String): String =
        buildString {
            append('"')
            text.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char < ' ' || char.code in ESCAPED_CODES -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }
}
