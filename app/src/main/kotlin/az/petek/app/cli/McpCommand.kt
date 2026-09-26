/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.PetekVersion
import az.petek.app.panel.PanelCore
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import az.petek.dashboard.application.UnavailablePanelBackend
import az.petek.dashboard.infrastructure.mcp.McpServer
import az.petek.dashboard.infrastructure.mcp.McpSettings
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/**
 * `petek mcp [--allow-writes]`: the Model Context Protocol server over stdio (ADR-0009, R10), for the project's AI
 * coding agent (`.mcp.json` written by `petek init`). It exposes the panel's use cases as tools over the same object
 * graph the web panel uses; stdout carries only the protocol, logs go to stderr and the log file. Read-only unless
 * `--allow-writes`: runs, approvals, teardown and exploration with writes are refused otherwise. Without a
 * configuration there is no site to test: the server still answers the handshake, but every tool explains that the
 * owner must name the site first (rule 12: nothing else is tested in its place). Runs until the client closes stdin.
 */
class McpCommand : PetekSubcommand(NAME) {
    private val allowWrites by option(
        "--allow-writes",
        help = "let the tools change state: runs, approvals, teardown, exploration with writes",
    ).flag()

    override fun help(context: Context): String =
        "Serve the Model Context Protocol over stdio for your AI coding agent (the panel's use cases as tools)."

    override suspend fun execute(): Int {
        val runtime = session.runtime
        if (!session.hasConfigurationFile) {
            val settings =
                McpSettings(NO_TARGET, runtime.workingDirectory.resolve(DEFAULT_EVIDENCE_DIR), allowWrites = false, PetekVersion.current)
            McpServer(
                UnavailablePanelBackend(UnavailablePanelBackend.NO_TARGET),
                settings,
                runtime.standardInput,
                runtime.standardOutput,
            ).serve()
            return ExitCodes.OK
        }
        val config = session.loadConfig()
        // stdout is the protocol: no live board, and every console line of the loggers goes to stderr.
        session.configureLogging(config, interactive = false)
        PanelCore
            .start(
                config = config,
                containers = runtime.panelContainers,
                workingDirectory = runtime.workingDirectory,
                capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
            ).use { core ->
                val settings =
                    McpSettings(
                        config.target.toString(),
                        config.evidenceDir,
                        allowWrites,
                        PetekVersion.current,
                        config.targets.associate { it.spec.name to it.spec.url.toString() },
                    )
                McpServer(core.backend, settings, runtime.standardInput, runtime.standardOutput).serve()
            }
        return ExitCodes.OK
    }

    companion object {
        const val NAME = "mcp"

        /** What `list_targets` reports when no configuration names a site. */
        const val NO_TARGET = "(no site to test was given: set PETEK_TARGET in .env)"
        private const val DEFAULT_EVIDENCE_DIR = "evidence"
    }
}
