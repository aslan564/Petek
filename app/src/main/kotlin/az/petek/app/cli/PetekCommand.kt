/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path

/**
 * `petek`: the root command. It only holds the global options (`--env-file`, `--verbose`, `--json`) and hands them to the
 * subcommand that runs; see [PetekSubcommand] for error handling and exit codes.
 */
class PetekCommand(
    private val runtime: CliRuntime = CliRuntime(),
) : SuspendingCliktCommand("petek") {
    private val envFile by option("--env-file", help = "configuration file (default: .env in the working directory)", metavar = "PATH")
        .path()
    private val verbose by option("--verbose", "-v", help = "debug logging and stack traces on errors").flag()
    private val json by option("--json", help = "print the command's result as one JSON document on stdout (logs stay on stderr)").flag()

    init {
        subcommands(
            InitCommand(),
            PlanCommand(),
            RunCommand(),
            ReportCommand(),
            TeardownCommand(),
            SmokeCommand(),
            DoctorCommand(),
            CapacityCommand(),
            ProbeCommand(),
            PanelCommand(),
            McpCommand(),
        )
    }

    override fun help(context: Context): String =
        "Pətək: many AI tester agents test a web application at once and report with evidence. " +
            "Configuration comes from .env and the environment (see .env.example)."

    override suspend fun run() {
        currentContext.obj = CliSession(runtime, envFile, verbose, json)
    }
}
