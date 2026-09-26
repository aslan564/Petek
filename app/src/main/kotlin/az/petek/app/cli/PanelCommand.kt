/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

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

/**
 * `petek panel` (also what `petek` does without a command): serves the web panel on 127.0.0.1 and opens it in the
 * browser; everything is chosen in the page. Without a configuration (no `--env-file`, no `.env`) there is no site
 * to test, so the command asks for one and exits with 2 (rule 12: never a stand-in site). Runs until the process is
 * stopped (IntelliJ's stop button, Ctrl+C).
 */
class PanelCommand : PetekSubcommand(NAME) {
    private val port by option("--port", help = "panel port (default 7070; the next free one when taken)")
        .int()
        .restrictTo(min = 0, max = 65535)
        .default(DEFAULT_PORT)
    private val noOpen by option("--no-open", help = "do not open the browser").flag()

    override fun help(context: Context): String =
        "Open the web panel: instructions, explorer, scenarios, orchestrator, live agents, reports."

    override suspend fun execute(): Int {
        val runtime = session.runtime
        if (!session.hasConfigurationFile) {
            echo(NO_TARGET, err = true)
            return ExitCodes.CONFIG_OR_ABORTED
        }
        val config = session.loadConfig()
        session.configureLogging(config, interactive = false)
        WebPanel
            .start(
                config = config,
                containers = runtime.panelContainers,
                workingDirectory = runtime.workingDirectory,
                capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                port = port,
            ).use { panel ->
                val url = panel.url
                echo("Pətək paneli: $url  (hədəf: ${config.targetLabel})")
                if (!noOpen && !runtime.openInBrowser(url.toString())) echo("Brauzeri özünüz açın: $url")
                awaitCancellation()
            }
    }

    companion object {
        const val NAME = "panel"
        const val DEFAULT_PORT = 7070

        /** Printed when no configuration names a site: the owner is asked, nothing is started. */
        const val NO_TARGET =
            "No site to test was given: there is no .env (and no --env-file). Which site should be tested? " +
                "Write it as PETEK_TARGET in .env (or run `petek init --target <url>`) and start the panel again."
    }
}
