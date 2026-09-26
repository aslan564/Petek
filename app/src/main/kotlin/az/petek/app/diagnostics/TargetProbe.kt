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

import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.RealtimeTransport
import az.petek.browser.domain.SessionOptions
import az.petek.campaign.domain.TargetProfile
import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI
import kotlin.time.Duration

/** One public page of the contract as the probe saw it. */
data class PageProbe(
    val key: String,
    val path: String,
    /** The anonymous GET, redirects not followed. */
    val http: HttpCheck,
    /** Where the browser ended up (after redirects and scripts); null when the page could not be opened. */
    val finalUrl: String?,
    val present: List<String>,
    val missing: List<String>,
    val error: String?,
    /** Why a missing element may be expected, shown next to the result. */
    val note: String? = null,
    /** False for a page whose elements only appear within a flow (`/verify` after a sign-up): reported, not required. */
    val elementsRequired: Boolean = true,
    /** The CAPTCHA widget the page shows (`recaptcha`, `hcaptcha`, `turnstile`, `captcha`), if any. */
    val captcha: String? = null,
) {
    val ready: Boolean
        get() = http is HttpCheck.Answered && http.status in SUCCESS && error == null && (missing.isEmpty() || !elementsRequired)

    private companion object {
        val SUCCESS = 200..299
    }
}

/** How `/test/...` answered: without a token (must be 401) and, for the configured target only, with it. */
data class TestApiProbe(
    val anonymous: HttpCheck,
    val withToken: TokenCheck,
) {
    val requiresToken: Boolean get() = (anonymous as? HttpCheck.Answered)?.status == UNAUTHORIZED
    val ready: Boolean get() = requiresToken && withToken.acceptable

    sealed interface TokenCheck {
        val acceptable: Boolean

        /** 200 or 404 for the probe number: the token is accepted. */
        data class Answered(
            val status: Int,
        ) : TokenCheck {
            override val acceptable: Boolean get() = status == OK || status == NOT_FOUND
        }

        data class Failed(
            val error: String,
        ) : TokenCheck {
            override val acceptable: Boolean get() = false
        }

        /** Not sent. [acceptable] tells whether that is fine (probing another URL) or a gap (no token configured). */
        data class NotSent(
            val reason: String,
            override val acceptable: Boolean,
        ) : TokenCheck
    }

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
    }
}

data class RealtimeProbe(
    val transports: Set<RealtimeTransport>,
    val details: List<String>,
    val error: String?,
)

data class ProbeReport(
    val target: URI,
    val pages: List<PageProbe>,
    val testApi: TestApiProbe,
    val realtime: RealtimeProbe,
) {
    /** Every public page answers 2xx with all contract elements, and the test API is guarded and accepts the token. */
    val ready: Boolean get() = pages.all { it.ready } && testApi.ready
}

/**
 * `petek probe`: how ready a target is for Pətək (docs/TARGET_CONTRACT.md), seen anonymously. It loads the public
 * pages (login, register, join, verify), reports their HTTP status and which contract `data-testid`s are present or
 * missing (the verify form exists only during a sign-up, so its absence is reported but tolerated), asks `/test/...`
 * whether it is guarded by the token, and watches the home page's network traffic for the
 * real-time transport. No form is submitted and nothing is written.
 *
 * The token is only sent when [oracle] is given, i.e. when the probed URL is the configured `PETEK_TARGET`, so it can
 * never leak to another host.
 */
class TargetProbe(
    private val browser: BrowserEngine,
    private val browserConfig: BrowserEngineConfig,
    private val http: HttpProbe,
    private val oracle: TargetOracle?,
    private val tokenNotSentReason: String,
    /** How long the home page is watched for live-update traffic (polling needs a few seconds to show). */
    private val observationWindow: Duration,
) {
    suspend fun probe(target: URI): ProbeReport {
        val testApi = TestApiProbe(http.get(resolve(target, Doctor.PROBE_PATH)), tokenCheck())
        val factory = browser.start(browserConfig)
        try {
            val session = factory.open(SessionOptions(label = "probe", baseUrl = target))
            try {
                val pages = PAGES.map { page -> probePage(session, target, page) }
                return ProbeReport(target, pages, testApi, realtime(session))
            } finally {
                session.close()
            }
        } finally {
            browser.stop()
        }
    }

    private suspend fun probePage(
        session: BrowserSession,
        target: URI,
        page: ContractPage,
    ): PageProbe {
        val path = TargetProfile.DEFAULT.path(page.key)
        val answer = http.get(resolve(target, path))
        return try {
            session.navigate(path)
            val selectors = page.selectorKeys.map { TargetProfile.DEFAULT.selector(it) }
            val (present, missing) = selectors.partition { session.count(it) > 0 }
            val captcha = runCatching { CaptchaSigns.detect(session.domSnapshot()) }.getOrNull()
            PageProbe(page.key, path, answer, session.currentUrl(), present, missing, null, page.note, page.elementsRequired, captcha)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PageProbe(page.key, path, answer, null, emptyList(), emptyList(), HttpProbe.describe(e), page.note, page.elementsRequired)
        }
    }

    private suspend fun realtime(session: BrowserSession): RealtimeProbe =
        try {
            session.navigate(TargetProfile.DEFAULT.path("home"))
            delay(observationWindow)
            val observation = session.networkObservation()
            RealtimeProbe(observation.transports, observation.details, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RealtimeProbe(emptySet(), emptyList(), HttpProbe.describe(e))
        }

    private suspend fun tokenCheck(): TestApiProbe.TokenCheck {
        val oracle = oracle ?: return TestApiProbe.TokenCheck.NotSent(tokenNotSentReason, acceptable = true)
        if (!oracle.isAvailable) {
            return TestApiProbe.TokenCheck.NotSent("PETEK_TEST_TOKEN is empty", acceptable = false)
        }
        return try {
            TestApiProbe.TokenCheck.Answered(oracle.get(Doctor.PROBE_PATH).status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestApiProbe.TokenCheck.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** A contract page and the selectors (keys of [TargetProfile.DEFAULT_SELECTORS]) it shows anonymously. */
    private data class ContractPage(
        val key: String,
        val selectorKeys: List<String>,
        val note: String? = null,
        val elementsRequired: Boolean = true,
    )

    companion object {
        private val PAGES =
            listOf(
                ContractPage("login", listOf("login.email", "login.password", "login.submit")),
                ContractPage(
                    "register",
                    listOf("register.name", "register.email", "register.phone", "register.password", "register.company", "register.submit"),
                ),
                ContractPage(
                    "join",
                    listOf("join.code", "join.name", "join.email", "join.phone", "join.password", "join.department", "join.submit"),
                ),
                ContractPage(
                    "verify",
                    listOf("verify.code", "verify.submit"),
                    note = "the code form only appears during a pending sign-up (/verify?email=...)",
                    elementsRequired = false,
                ),
            )

        /** [path] on [target], keeping the target's own path prefix. */
        fun resolve(
            target: URI,
            path: String,
        ): URI = URI(target.toString().trimEnd('/') + "/" + path.trimStart('/'))
    }
}
