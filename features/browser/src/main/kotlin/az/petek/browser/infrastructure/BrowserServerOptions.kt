/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Options for Playwright's `BrowserType.launchServer`, serialized as the JSON object [BundledScripts.browserServer]
 * expects. The server always listens on 127.0.0.1 with a random port: a browser server accepts any client that knows
 * its URL, so it must never be reachable from other machines.
 */
internal data class BrowserServerOptions(
    val headless: Boolean,
    val launchTimeout: Duration = 60.seconds,
    /** Overrides the Chromium binary; null uses the one Playwright installed. */
    val executablePath: Path? = null,
) {
    fun toJson(): String =
        buildString {
            append("{\"headless\":").append(headless)
            append(",\"host\":\"127.0.0.1\",\"port\":0")
            append(",\"timeout\":").append(launchTimeout.inWholeMilliseconds)
            if (executablePath != null) append(",\"executablePath\":").append(jsonString(executablePath.toString()))
            append('}')
        }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char < ' ' -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }
}
