/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.application

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.explorer.domain.SiteOrigin
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/**
 * The only view of a browser session the crawl passes get: it can look (navigate, snapshot, screenshot, GET) but
 * never act. Clicking, typing, selecting, non-GET requests, saving storage and closing throw
 * [IllegalStateException], and navigation or requests outside [origin] are refused the same way. This makes
 * "the explorer never submits anything outside TRIAL_TOUCH and never leaves the target" a property of the code, not
 * of the crawler's good behaviour.
 */
internal class ReadOnlyBrowserSession(
    private val delegate: BrowserSession,
    private val origin: SiteOrigin,
) : BrowserSession by delegate {
    override suspend fun navigate(pathOrUrl: String) {
        // The address that was checked is exactly the one passed on.
        delegate.navigate(requireOnOrigin(pathOrUrl))
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult {
        if (!method.equals("GET", ignoreCase = true) || body != null) refuse("$method request")
        return delegate.request("GET", requireOnOrigin(path), null)
    }

    override suspend fun click(ref: Int) = refuse("click")

    override suspend fun fill(
        ref: Int,
        text: String,
        submit: Boolean,
    ) = refuse("typing")

    override suspend fun select(
        ref: Int,
        option: String,
    ) = refuse("selecting")

    override suspend fun clickSelector(selector: String) = refuse("click")

    override suspend fun fillSelector(
        selector: String,
        text: String,
    ) = refuse("typing")

    override suspend fun selectSelector(
        selector: String,
        option: String,
    ) = refuse("selecting")

    override suspend fun saveStorageState(path: Path) = refuse("saving the storage state")

    override suspend fun close() = refuse("closing a session it does not own")

    /** [pathOrUrl] trimmed when it is an address on [origin] (absolute, or a path starting with one `/`); else refused. */
    private fun requireOnOrigin(pathOrUrl: String): String {
        val address = pathOrUrl.trim()
        val uri =
            try {
                URI(address)
            } catch (_: URISyntaxException) {
                refuse("opening an unparsable address")
            }
        val onOrigin =
            if (uri.isAbsolute) {
                origin.contains(uri)
            } else {
                uri.scheme == null && uri.rawAuthority == null && address.startsWith("/") && !address.startsWith("//")
            }
        if (!onOrigin) refuse("leaving the target origin")
        return address
    }

    private fun refuse(what: String): Nothing = throw IllegalStateException("The explorer is read-only here: $what is not allowed")
}
