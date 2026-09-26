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

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** How a session's own [Playwright] instance obtains its [Browser]. Always called on the session's thread. */
internal sealed interface BrowserConnector {
    fun connect(playwright: Playwright): Browser

    /** [BrowserEngineConfig.ignoreTlsErrors] of the engine, applied to every context opened through this connector. */
    val ignoreTlsErrors: Boolean

    /**
     * SHARED_SERVER: a Playwright-protocol connection to the run's browser server. Contexts created through the
     * connection belong to it and are removed by the server when the connection closes.
     */
    class SharedServer(
        private val wsEndpoint: String,
        private val slowMo: Duration,
        private val connectTimeout: Duration = 30.seconds,
        override val ignoreTlsErrors: Boolean = false,
    ) : BrowserConnector {
        override fun connect(playwright: Playwright): Browser =
            playwright.chromium().connect(
                wsEndpoint,
                BrowserType
                    .ConnectOptions()
                    .setSlowMo(slowMo.toDouble(DurationUnit.MILLISECONDS))
                    .setTimeout(connectTimeout.toDouble(DurationUnit.MILLISECONDS)),
            )

        override fun toString(): String = "SharedServer(slowMo=$slowMo)"
    }

    /** PER_SESSION: the session launches, and owns, a Chromium of its own. */
    class OwnBrowser(
        private val headless: Boolean,
        private val slowMo: Duration,
        override val ignoreTlsErrors: Boolean = false,
    ) : BrowserConnector {
        override fun connect(playwright: Playwright): Browser =
            playwright.chromium().launch(
                BrowserType
                    .LaunchOptions()
                    .setHeadless(headless)
                    .setSlowMo(slowMo.toDouble(DurationUnit.MILLISECONDS)),
            )

        override fun toString(): String = "OwnBrowser(headless=$headless, slowMo=$slowMo)"
    }
}
