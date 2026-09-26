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

package az.petek.app.testing

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.browser.testing.FakeBrowserSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Browser engine for app tests (no Chromium may be launched outside the browser module): every session is a
 * [FakeBrowserSession] prepared by [prepare]. Records starts, stops and opened sessions.
 */
class FakeBrowserEngine(
    private val failStart: String? = null,
    private val prepare: (FakeBrowserSession, SessionOptions) -> Unit = { _, _ -> },
) : BrowserEngine {
    val configs = CopyOnWriteArrayList<BrowserEngineConfig>()
    val sessions = CopyOnWriteArrayList<FakeBrowserSession>()
    val options = CopyOnWriteArrayList<SessionOptions>()
    private val stops = AtomicInteger()

    val stopCount: Int get() = stops.get()

    override suspend fun start(config: BrowserEngineConfig): BrowserSessionFactory {
        configs += config
        failStart?.let { throw BrowserActionException(it) }
        return BrowserSessionFactory { options ->
            FakeBrowserSession(options.label).also { session ->
                prepare(session, options)
                this.options += options
                sessions += session
            }
        }
    }

    override suspend fun stop() {
        stops.incrementAndGet()
    }
}
