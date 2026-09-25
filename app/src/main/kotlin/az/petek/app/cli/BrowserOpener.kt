/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Opens a file or URL in the user's default browser without blocking the caller. */
internal object BrowserOpener {
    fun open(target: String): Boolean {
        val uri = if (target.startsWith("http://") || target.startsWith("https://")) URI(target) else Path.of(target).toUri()
        val os = System.getProperty("os.name").lowercase()
        val command =
            when {
                "linux" in os -> listOf("xdg-open", uri.toString())
                "mac" in os -> listOf("open", uri.toString())
                else -> null
            }
        if (command != null) {
            val started = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }.getOrNull()
            if (started != null) {
                // xdg-open returns quickly after handing over to the browser; do not wait longer than that.
                return runCatching { !started.waitFor(5, TimeUnit.SECONDS) || started.exitValue() == 0 }.getOrDefault(false)
            }
        }
        return runCatching {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                Desktop.getDesktop().browse(uri).let { true }
        }.getOrDefault(false)
    }
}
