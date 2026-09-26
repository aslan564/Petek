/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.config.PetekConfig
import az.petek.app.diagnostics.SmokeCheck
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `petek smoke [--url <url>]`: opens the target (default `PETEK_TARGET`) in Chromium and prints its title, how many
 * interactive elements an agent would see and which live-update transports the page uses; saves
 * `<evidence>/smoke.png`. No login, no writes.
 */
class SmokeCommand : PetekSubcommand("smoke") {
    private val url by urlOption("page to open (default: PETEK_TARGET)")

    override fun help(context: Context): String = "Open the target in Chromium and show what an agent would see."

    override suspend fun execute(): Int =
        withContainer { container ->
            val config = container.config
            val target = url ?: config.target
            TargetGuard.requireAllowed(config.targetPolicy, target)
            val check =
                SmokeCheck(
                    browser = container.browserEngine,
                    browserConfig = container.browserConfig(),
                    screenshot = config.evidenceDir.resolve(SCREENSHOT),
                    observationWindow = session.runtime.observationWindow,
                )
            val result = check.run(target)
            val transports =
                result.network.transports
                    .map { it.name.lowercase() }
                    .sorted()
            if (json) {
                emitJson(
                    buildJsonObject {
                        put("target", PetekConfig.masked(target))
                        put("url", result.url)
                        put("title", result.title)
                        put("elements", result.elements)
                        putJsonArray("realtime") { transports.forEach { add(it) } }
                        put("screenshot", result.screenshot.toString())
                    },
                )
                return@withContainer ExitCodes.OK
            }
            echo("Opened ${PetekConfig.masked(target)} (now at ${result.url})")
            echo("Title: ${result.title.ifBlank { "(none)" }}")
            echo("Interactive elements in the snapshot: ${result.elements}")
            echo("Real-time transports: ${transports.joinToString(", ").ifEmpty { "none detected" }}")
            result.network.details.forEach { echo("  $it") }
            echo("Screenshot: ${result.screenshot}")
            ExitCodes.OK
        }

    companion object {
        const val SCREENSHOT = "smoke.png"
    }
}
