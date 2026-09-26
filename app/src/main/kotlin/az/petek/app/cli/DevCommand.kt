/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.diagnostics.HttpCheck
import az.petek.app.diagnostics.HttpProbe
import az.petek.app.panel.WebPanel
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.net.URI
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `petek dev`: start the panel next to the project's app once the app is up (Faza 12). It waits until the health URL
 * answers HTTP 2xx (`--health`, else `health_url` of `.petek/petek.yaml`, else the target's own address), polling every
 * two seconds for at most `--wait` seconds, then serves the panel as `petek panel` does. The site is `PETEK_TARGET` from
 * `.env` (`petek init` writes both files); without it nothing starts (rule 12).
 */
class DevCommand : PetekSubcommand("dev") {
    private val port by option("--port", help = "panel port (default 7070; the next free one when taken)")
        .int()
        .restrictTo(min = 0, max = 65535)
        .default(PanelCommand.DEFAULT_PORT)
    private val noOpen by option("--no-open", help = "do not open the browser").flag()
    private val health by option(
        "--health",
        help = "URL or path that answers 2xx once the app is up (default: health_url of .petek/petek.yaml)",
    )
    private val wait by option("--wait", help = "seconds to wait for the app (default 180)").int().restrictTo(min = 1).default(DEFAULT_WAIT)

    override fun help(context: Context): String = "Wait for the project's app to start, then open the panel next to it."

    override suspend fun execute(): Int {
        val runtime = session.runtime
        if (!session.hasConfigurationFile) {
            echo(PanelCommand.NO_TARGET, err = true)
            return ExitCodes.CONFIG_OR_ABORTED
        }
        val config = session.loadConfig()
        session.configureLogging(config, interactive = false)
        TargetGuard.requireAllowed(config.targetPolicy, config.target)
        val url = healthUrl(config.target)
        echo("Tətbiqin qalxması gözlənilir: $url (ən çox $wait san)…")
        if (!waitFor(url)) {
            echo("Tətbiq $wait saniyədə cavab vermədi: $url. Tətbiqi başladın və ya --health / --wait verin.", err = true)
            return ExitCodes.CONFIG_OR_ABORTED
        }
        WebPanel
            .start(
                config = config,
                containers = runtime.panelContainers,
                workingDirectory = runtime.workingDirectory,
                capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                port = port,
            ).use { panel ->
                echo("Tətbiq qalxdı. Pətək paneli: ${panel.url}  (hədəf: ${config.targetLabel})")
                if (!noOpen && !runtime.openInBrowser(panel.url.toString())) echo("Brauzeri özünüz açın: ${panel.url}")
                awaitCancellation()
            }
    }

    /** `--health`, else `health_url:` of the project profile, resolved against [target]; else [target] itself. */
    private fun healthUrl(target: URI): URI {
        val given = health ?: profileHealthUrl()
        if (given.isNullOrBlank()) return target
        val uri = URI(given.trim())
        return if (uri.isAbsolute) uri else URI(target.toString().trimEnd('/') + "/" + given.trim().trimStart('/'))
    }

    private fun profileHealthUrl(): String? {
        val profile = session.runtime.workingDirectory.resolve(PROFILE)
        if (!Files.isRegularFile(profile)) return null
        return Files
            .readAllLines(profile)
            .map { it.substringBefore('#').trim() }
            .firstOrNull { it.startsWith("health_url:") }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"', '\'')
    }

    private suspend fun waitFor(url: URI): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + wait.seconds
        HttpProbe().use { http ->
            while (deadline.hasNotPassedNow()) {
                val answer = http.get(url)
                if (answer is HttpCheck.Answered && answer.status in SUCCESS) return true
                delay(POLL)
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_WAIT = 180
        const val PROFILE = ".petek/petek.yaml"
        private val SUCCESS = 200..299
        private val POLL = 2.seconds
    }
}
