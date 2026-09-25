/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.WaitOutcome
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The session an agent gets from the runner: every browser call it makes (observe or act) is a sign of life for the
 * [InactivityWatchdog], reported when the call starts and when it returns. This keeps the watchdog meaningful on its
 * own, independent of how the composition root wires the agent's evidence recorder ([ProgressTrackingRecorder] adds
 * recorded evidence as a second source): an agent that keeps driving its browser is never blocked, while one stuck in
 * a single call or outside the browser (a hung LLM request) is. Closing the session is the runner's job and is not
 * progress.
 */
internal class ProgressReportingSession(
    private val delegate: BrowserSession,
    private val onProgress: () -> Unit,
) : BrowserSession by delegate {
    private inline fun <T> tracked(call: () -> T): T {
        onProgress()
        return try {
            call()
        } finally {
            onProgress()
        }
    }

    override suspend fun navigate(pathOrUrl: String) = tracked { delegate.navigate(pathOrUrl) }

    override suspend fun snapshot(): PageSnapshot = tracked { delegate.snapshot() }

    override suspend fun click(ref: Int) = tracked { delegate.click(ref) }

    override suspend fun fill(
        ref: Int,
        text: String,
        submit: Boolean,
    ) = tracked { delegate.fill(ref, text, submit) }

    override suspend fun select(
        ref: Int,
        option: String,
    ) = tracked { delegate.select(ref, option) }

    override suspend fun clickSelector(selector: String) = tracked { delegate.clickSelector(selector) }

    override suspend fun fillSelector(
        selector: String,
        text: String,
    ) = tracked { delegate.fillSelector(selector, text) }

    override suspend fun selectSelector(
        selector: String,
        option: String,
    ) = tracked { delegate.selectSelector(selector, option) }

    override suspend fun readText(selector: String): String? = tracked { delegate.readText(selector) }

    override suspend fun readAttribute(
        selector: String,
        attribute: String,
    ): String? = tracked { delegate.readAttribute(selector, attribute) }

    override suspend fun waitForText(
        text: String,
        timeout: Duration,
    ): WaitOutcome = tracked { delegate.waitForText(text, timeout) }

    override suspend fun waitForSelector(
        selector: String,
        timeout: Duration,
    ): WaitOutcome = tracked { delegate.waitForSelector(selector, timeout) }

    override suspend fun isTextVisible(text: String): Boolean = tracked { delegate.isTextVisible(text) }

    override suspend fun isSelectorVisible(selector: String): Boolean = tracked { delegate.isSelectorVisible(selector) }

    override suspend fun count(selector: String): Int = tracked { delegate.count(selector) }

    override suspend fun currentUrl(): String = tracked { delegate.currentUrl() }

    override suspend fun screenshot(): ByteArray = tracked { delegate.screenshot() }

    override suspend fun accessibilitySnapshot(): String = tracked { delegate.accessibilitySnapshot() }

    override suspend fun domSnapshot(): String = tracked { delegate.domSnapshot() }

    override suspend fun saveStorageState(path: Path) = tracked { delegate.saveStorageState(path) }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult = tracked { delegate.request(method, path, body) }

    override suspend fun networkObservation(): NetworkObservation = tracked { delegate.networkObservation() }
}
