/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.browser.domain.BrowserContextLostException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.ObservedMutation
import az.petek.browser.domain.PageHealth
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.WaitOutcome
import az.petek.core.time.HarnessTimestamp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.time.Duration

/**
 * A tester's session that survives a crashed browser context (docs/PLAN.md Faza 3): when a call fails with
 * [BrowserContextLostException], [reopen] gives a new context for the same identity (with its saved `storage_state`
 * when the tester had signed in, so it is still signed in), the page the tester was on is opened again, [onRestored]
 * records it, and the call is made once more. At most [maxRestores] times per run; after that the loss is final and
 * the exception reaches the caller as before. The old context is closed quietly. Every other failure passes through.
 */
internal class RestoringBrowserSession(
    initial: BrowserSession,
    private val reopen: suspend () -> BrowserSession,
    private val onRestored: suspend (restored: Int, reason: String, url: String?) -> Unit,
    private val maxRestores: Int = DEFAULT_MAX_RESTORES,
) : BrowserSession {
    @Volatile
    private var current: BrowserSession = initial

    @Volatile
    private var lastUrl: String? = null

    private val restoring = Mutex()
    private var restores = 0

    /** The session calls go to right now (a restored one after a crash). */
    val active: BrowserSession get() = current

    override val label: String get() = current.label

    private suspend fun <T> guarded(call: suspend (BrowserSession) -> T): T {
        val used = current
        return try {
            call(used)
        } catch (e: BrowserContextLostException) {
            if (!restore(used, e)) throw e
            call(current)
        }
    }

    /** True when a new context is in place (by this call or by a concurrent one that saw the same loss). */
    private suspend fun restore(
        lost: BrowserSession,
        cause: BrowserContextLostException,
    ): Boolean =
        restoring.withLock {
            if (current !== lost) return@withLock true
            if (restores >= maxRestores) return@withLock false
            restores++
            runCatching { lost.close() }
            val fresh = reopen()
            current = fresh
            val url = lastUrl
            if (url != null) runCatching { fresh.navigate(url) }
            onRestored(restores, cause.message.orEmpty(), url)
            true
        }

    override suspend fun navigate(pathOrUrl: String) {
        guarded { it.navigate(pathOrUrl) }
        lastUrl = pathOrUrl
    }

    override suspend fun snapshot(): PageSnapshot = guarded { it.snapshot() }

    override suspend fun click(ref: Int) = guarded { it.click(ref) }

    override suspend fun fill(
        ref: Int,
        text: String,
        submit: Boolean,
    ) = guarded { it.fill(ref, text, submit) }

    override suspend fun select(
        ref: Int,
        option: String,
    ) = guarded { it.select(ref, option) }

    override suspend fun clickSelector(selector: String) = guarded { it.clickSelector(selector) }

    override suspend fun fillSelector(
        selector: String,
        text: String,
    ) = guarded { it.fillSelector(selector, text) }

    override suspend fun selectSelector(
        selector: String,
        option: String,
    ) = guarded { it.selectSelector(selector, option) }

    override suspend fun readText(selector: String): String? = guarded { it.readText(selector) }

    override suspend fun readAttribute(
        selector: String,
        attribute: String,
    ): String? = guarded { it.readAttribute(selector, attribute) }

    override suspend fun waitForText(
        text: String,
        timeout: Duration,
    ): WaitOutcome = guarded { it.waitForText(text, timeout) }

    override suspend fun waitForSelector(
        selector: String,
        timeout: Duration,
    ): WaitOutcome = guarded { it.waitForSelector(selector, timeout) }

    override suspend fun isTextVisible(text: String): Boolean = guarded { it.isTextVisible(text) }

    override suspend fun isSelectorVisible(selector: String): Boolean = guarded { it.isSelectorVisible(selector) }

    override suspend fun count(selector: String): Int = guarded { it.count(selector) }

    override suspend fun currentUrl(): String = guarded { it.currentUrl() }.also { lastUrl = it }

    override suspend fun screenshot(): ByteArray = guarded { it.screenshot() }

    override suspend fun accessibilitySnapshot(): String = guarded { it.accessibilitySnapshot() }

    override suspend fun domSnapshot(): String = guarded { it.domSnapshot() }

    override suspend fun saveStorageState(path: Path) = guarded { it.saveStorageState(path) }

    override suspend fun setCorrelationId(id: String?) = guarded { it.setCorrelationId(id) }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult = guarded { it.request(method, path, body) }

    override suspend fun networkObservation(): NetworkObservation = guarded { it.networkObservation() }

    override suspend fun drainDialogs(): List<DialogEvent> = guarded { it.drainDialogs() }

    override suspend fun mutations(since: HarnessTimestamp): List<ObservedMutation> = guarded { it.mutations(since) }

    override suspend fun health(
        since: HarnessTimestamp,
        slowAfter: Duration,
    ): PageHealth = guarded { it.health(since, slowAfter) }

    override suspend fun links(): List<String> = guarded { it.links() }

    override suspend fun goBack(): Boolean = guarded { it.goBack() }

    override suspend fun clearCookies() = guarded { it.clearCookies() }

    override suspend fun horizontalOverflow(
        width: Int,
        height: Int,
    ): Int? = guarded { it.horizontalOverflow(width, height) }

    override suspend fun close() = current.close()

    companion object {
        const val DEFAULT_MAX_RESTORES = 2
    }
}
