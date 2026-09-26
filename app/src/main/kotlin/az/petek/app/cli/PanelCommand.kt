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

package az.petek.app.cli

import az.petek.app.diagnostics.HttpProbe
import az.petek.app.diagnostics.HttpTargetReachability
import az.petek.app.init.InitTemplates
import az.petek.app.panel.PanelSetup
import az.petek.app.panel.WebPanel
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import az.petek.dashboard.domain.SiteSetup
import az.petek.dashboard.infrastructure.SetupServer
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.net.URI

/**
 * `petek panel` (also what `petek` does without a command): serves the web panel on 127.0.0.1 and opens it in the
 * browser; everything is chosen in the page. Without a configuration (no `--env-file`, no `.env`) there is no site
 * to test, so the browser first shows one question, which site (rule 12: the owner is asked, never a stand-in site);
 * the answer is written to `.env` and the panel opens for it. Runs until the process is stopped (IntelliJ's stop
 * button, Ctrl+C).
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
        if (!session.hasConfigurationFile) return askForTheSite(runtime)
        startPanel(runtime).use { panel ->
            if (!noOpen && !runtime.openInBrowser(panel.url.toString())) echo("Brauzeri özünüz açın: ${panel.url}")
            awaitCancellation()
        }
    }

    /**
     * No configuration names a site to test (rule 12): the browser asks the owner which one, and nothing else starts
     * until the answer comes ([PanelSetup]). The answer is written to `.env`, the panel starts for it in this process
     * and the setup page moves on to it.
     */
    private suspend fun askForTheSite(runtime: CliRuntime): Int {
        val opened = CompletableDeferred<WebPanel>()
        val probe = if (runtime.siteReachability == null) HttpProbe() else null
        try {
            val setup =
                PanelSetup(
                    envFile = runtime.workingDirectory.resolve(CliSession.DEFAULT_ENV_FILE),
                    templates = InitTemplates.bundled(),
                    reachability = runtime.siteReachability ?: HttpTargetReachability(checkNotNull(probe)),
                    open = { startPanel(runtime).also(opened::complete).url },
                )
            val (server, url) = setupPage(setup)
            server.use {
                echo("Hansı saytın test ediləcəyini brauzerdə yazın: $url")
                if (!noOpen && !runtime.openInBrowser(url.toString())) echo("Brauzeri özünüz açın: $url")
                opened.await().use { awaitCancellation() }
            }
        } finally {
            probe?.close()
        }
    }

    /** The setup page on [port], or the next free one when it is taken (another panel, another program). */
    private fun setupPage(setup: SiteSetup): Pair<SetupServer, URI> {
        var last: Exception? = null
        for (candidate in WebPanel.portCandidates(port)) {
            val server = SetupServer(setup, port = candidate)
            try {
                return server to server.start()
            } catch (e: IllegalStateException) {
                server.close()
                last = e
            }
        }
        throw IllegalStateException("The setup page could not start on any port", last)
    }

    /** Loads the configuration, starts the panel for it and says where it runs. */
    private fun startPanel(runtime: CliRuntime): WebPanel {
        val config = session.loadConfig()
        session.configureLogging(config, interactive = false)
        val panel =
            WebPanel.start(
                config = config,
                containers = runtime.panelContainers,
                workingDirectory = runtime.workingDirectory,
                capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                port = port,
            )
        echo("Pətək paneli: ${panel.url}  (hədəf: ${config.targetLabel})")
        return panel
    }

    companion object {
        const val NAME = "panel"
        const val DEFAULT_PORT = 7070

        /** Printed by `petek dev` when no configuration names a site: the owner is asked, nothing is started. */
        const val NO_TARGET =
            "No site to test was given: there is no .env (and no --env-file). Which site should be tested? " +
                "Write it as PETEK_TARGET in .env (or run `petek init --target <url>`) and start the panel again."
    }
}
