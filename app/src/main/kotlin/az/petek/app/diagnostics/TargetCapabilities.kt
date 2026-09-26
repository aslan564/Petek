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

import az.petek.browser.domain.RealtimeTransport

/**
 * What a target supports, seen before an exploration or a run (Faza 10, ADR-0010): whether it has the test API,
 * where the testers' mail comes from, how it pushes live updates, and signs of a CAPTCHA or a rate limit on its public
 * pages. Pətək does not fight a CAPTCHA or a rate limit: it records them, and the report says which sign-in paths they
 * block.
 */
data class TargetCapabilities(
    val testApi: TestApiSupport,
    val mailSource: String,
    val realtime: Set<RealtimeTransport>,
    /** Pages showing a CAPTCHA widget, with the kind (`recaptcha`, `hcaptcha`, `turnstile`, `captcha`). */
    val captcha: Map<String, String>,
    /** Pages answering 429 (too many requests). */
    val rateLimited: List<String>,
) {
    enum class TestApiSupport { READY, GUARDED_TOKEN_MISSING, TOKEN_REJECTED, ABSENT }

    /** One line for the owner, in Azerbaijani. */
    fun summary(): String =
        buildString {
            append(
                when (testApi) {
                    TestApiSupport.READY -> "test API var və token qəbul olunur"
                    TestApiSupport.GUARDED_TOKEN_MISSING -> "test API var, amma token verilməyib"
                    TestApiSupport.TOKEN_REJECTED -> "test API tokeni rədd edir"
                    TestApiSupport.ABSENT -> "test API yoxdur (oracle N/A)"
                },
            )
            append("; poçt: ").append(mailSource)
            append("; real-time: ").append(if (realtime.isEmpty()) "görünmədi" else realtime.joinToString { it.name.lowercase() })
            append("; CAPTCHA: ").append(if (captcha.isEmpty()) "görünmədi" else captcha.entries.joinToString { "${it.key} (${it.value})" })
            if (rateLimited.isNotEmpty()) append("; rate limit (429): ").append(rateLimited.joinToString())
        }

    companion object {
        fun of(
            report: ProbeReport,
            mailSource: String,
        ): TargetCapabilities {
            val api = report.testApi
            val support =
                when {
                    api.ready -> TestApiSupport.READY
                    !api.requiresToken -> TestApiSupport.ABSENT
                    api.withToken is TestApiProbe.TokenCheck.NotSent -> TestApiSupport.GUARDED_TOKEN_MISSING
                    else -> TestApiSupport.TOKEN_REJECTED
                }
            return TargetCapabilities(
                testApi = support,
                mailSource = mailSource,
                realtime = report.realtime.transports,
                captcha = report.pages.mapNotNull { page -> page.captcha?.let { page.key to it } }.toMap(),
                rateLimited = report.pages.filter { (it.http as? HttpCheck.Answered)?.status == TOO_MANY_REQUESTS }.map { it.key },
            )
        }

        private const val TOO_MANY_REQUESTS = 429
    }
}

/** Recognises a CAPTCHA widget in a page's HTML; pure. */
object CaptchaSigns {
    private val KINDS =
        listOf(
            "turnstile" to listOf("cf-turnstile", "challenges.cloudflare.com/turnstile"),
            "hcaptcha" to listOf("h-captcha", "hcaptcha.com"),
            "recaptcha" to listOf("g-recaptcha", "google.com/recaptcha", "recaptcha/api.js"),
            "captcha" to listOf("captcha"),
        )

    /** The CAPTCHA kind shown by [html], or null when the page shows none. */
    fun detect(html: String): String? {
        val text = html.lowercase()
        return KINDS.firstOrNull { (_, markers) -> markers.any { it in text } }?.first
    }
}
