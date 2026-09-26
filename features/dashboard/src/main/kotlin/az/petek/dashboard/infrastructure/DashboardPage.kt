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

package az.petek.dashboard.infrastructure

import java.security.SecureRandom
import java.util.Base64

/**
 * The panel's single self-contained page: `index.html` with the stylesheet and the scripts of every screen inlined at
 * start-up (no CDN, no second request). Its inline `<style>` and `<script>` carry a fresh nonce per response and the
 * Content-Security-Policy allows nothing else: no other script, no inline handlers, no foreign origin. The per-process
 * [token] for mutating requests is written into a `<meta>` tag only other pages of this origin can read. The page
 * escapes every value it renders (it only ever writes data with `textContent`); [defaultTarget], the site the
 * instruction form starts with, is written HTML-escaped into a `<meta>` tag as well.
 */
internal class DashboardPage(
    private val token: String,
    defaultTarget: String? = null,
) {
    private val target: String = escape(defaultTarget.orEmpty())

    private val template: String =
        read("index.html")
            .replace(STYLE_MARK, read("panel.css"))
            .replace(SCRIPT_MARK, SCRIPTS.joinToString("\n") { read(it) })

    private val random = SecureRandom()

    init {
        check(NONCE_MARK in template && TOKEN_MARK in template) { "the panel page misses its $NONCE_MARK or $TOKEN_MARK placeholder" }
    }

    /** A page and the policy that goes with it. */
    class Rendered(
        val html: String,
        val contentSecurityPolicy: String,
    )

    fun render(): Rendered {
        val nonce = nonce()
        return Rendered(
            html = template.replace(NONCE_MARK, nonce).replace(TOKEN_MARK, token).replace(TARGET_MARK, target),
            contentSecurityPolicy =
                "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; img-src 'self' data:; " +
                    "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        )
    }

    private fun nonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private companion object {
        const val FOLDER = "/az/petek/dashboard/infrastructure/panel/"
        const val NONCE_MARK = "{{NONCE}}"
        const val TOKEN_MARK = "{{TOKEN}}"
        const val TARGET_MARK = "{{TARGET}}"
        const val STYLE_MARK = "/*{{STYLE}}*/"
        const val SCRIPT_MARK = "/*{{SCRIPT}}*/"
        const val NONCE_BYTES = 18

        /** In load order: the shared core first, one file per screen, the start-up last. */
        val SCRIPTS =
            listOf(
                "core.js",
                "screen-instructions.js",
                "screen-explorer.js",
                "screen-scenarios.js",
                "screen-orchestrator.js",
                "screen-agents.js",
                "screen-reports.js",
                "app.js",
            )

        /** Escapes text for a double-quoted HTML attribute. */
        fun escape(text: String): String =
            text
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

        fun read(name: String): String =
            checkNotNull(DashboardPage::class.java.getResourceAsStream(FOLDER + name)) { "panel resource $name is missing" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
