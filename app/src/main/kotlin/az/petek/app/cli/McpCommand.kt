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
import az.petek.app.config.ConfigLoader
import az.petek.app.config.PetekConfig
import az.petek.app.demo.DemoTarget
import az.petek.app.panel.PanelCore
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import az.petek.dashboard.infrastructure.mcp.McpServer
import az.petek.dashboard.infrastructure.mcp.McpSettings
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/**
 * `petek mcp [--allow-writes] [--demo]`: the Model Context Protocol server over stdio (ADR-0009, R10), for the
 * project's AI coding agent (`.mcp.json` written by `petek init`). It exposes the panel's use cases as tools over the
 * same object graph the web panel uses; stdout carries only the protocol, logs go to stderr and the log file. Read-only
 * unless `--allow-writes`: runs, approvals, teardown and exploration with writes are refused otherwise. Without a
 * configuration it serves the local fake KadroHR, like `petek panel`. Runs until the client closes stdin.
 */
class McpCommand : PetekSubcommand(NAME) {
    private val allowWrites by option(
        "--allow-writes",
        help = "let the tools change state: runs, approvals, teardown, exploration with writes",
    ).flag()
    private val demo by option("--demo", help = "use the local fake KadroHR even when .env exists").flag()

    override fun help(context: Context): String =
        "Serve the Model Context Protocol over stdio for your AI coding agent (the panel's use cases as tools)."

    override suspend fun execute(): Int {
        val runtime = session.runtime
        val useDemo = demo || !session.hasConfigurationFile
        val demoTarget = if (useDemo) DemoTarget(runtime.workingDirectory) else null
        try {
            val config = demoTarget?.let { demoConfig(it) } ?: session.loadConfig()
            // stdout is the protocol: no live board, and every console line of the loggers goes to stderr.
            session.configureLogging(config, interactive = false)
            PanelCore
                .start(
                    config = config,
                    containers = runtime.panelContainers,
                    workingDirectory = runtime.workingDirectory,
                    capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                ).use { core ->
                    val settings = McpSettings(config.target.toString(), config.evidenceDir, allowWrites, PetekVersion.current)
                    McpServer(core.backend, settings, runtime.standardInput, runtime.standardOutput).serve()
                }
            return ExitCodes.OK
        } finally {
            demoTarget?.close()
        }
    }

    private fun demoConfig(target: DemoTarget): PetekConfig {
        val file = target.start()
        val runtime = session.runtime
        return ConfigLoader(runtime.environment(), runtime.workingDirectory, runtime.identitySecrets).load(file)
    }

    companion object {
        const val NAME = "mcp"
    }
}
