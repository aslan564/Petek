/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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

    /**
     * SHARED_SERVER: a Playwright-protocol connection to the run's browser server. Contexts created through the
     * connection belong to it and are removed by the server when the connection closes.
     */
    class SharedServer(
        private val wsEndpoint: String,
        private val slowMo: Duration,
        private val connectTimeout: Duration = 30.seconds,
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
