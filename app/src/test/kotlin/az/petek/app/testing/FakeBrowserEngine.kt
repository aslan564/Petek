/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
