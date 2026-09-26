/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.config.ConfigException
import az.petek.app.diagnostics.CheckResult
import az.petek.app.diagnostics.CheckStatus
import az.petek.app.diagnostics.Doctor
import az.petek.app.diagnostics.HttpProbe
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.TextColors
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `petek doctor`: a ✓/✗ table of everything a run depends on (see [Doctor]). An invalid configuration is itself a
 * row, with every problem listed, instead of an error. Exit code 0 when every check passes, 2 when the configuration
 * is invalid or the target policy refuses the target (nothing else can work then), 1 when another check fails.
 */
class DoctorCommand : PetekSubcommand("doctor") {
    override fun help(context: Context): String =
        "Check the configuration, target, browser, mailbox, test API, site ownership and LLM provider."

    override suspend fun execute(): Int {
        val rows =
            try {
                val config = session.loadConfig()
                session.configureLogging(config, interactive = terminal.terminalInfo.outputInteractive)
                session.runtime.containers(config).use { container ->
                    HttpProbe().use { http -> listOf(Doctor.configurationValid(config)) + Doctor(container, http).run() }
                }
            } catch (e: ConfigException) {
                Doctor.configurationFailed(e.problems)
            }
        if (json) {
            emitJson(
                buildJsonObject {
                    put("ok", rows.all { it.status == CheckStatus.OK })
                    putJsonArray("checks") {
                        rows.forEach { row ->
                            addJsonObject {
                                put("name", row.name)
                                put("status", row.status.name)
                                put("detail", row.detail)
                            }
                        }
                    }
                },
            )
            return exitCodeOf(rows)
        }
        echo(
            TextTable.render(listOf("", "Check", "Result"), rows.map { listOf(symbol(it.status), it.name, it.detail) }) { column, cell ->
                if (column == 0) colored(cell) else cell
            },
        )
        return exitCodeOf(rows)
    }

    /** Like every other command: a configuration or target that stops everything is 2, other failed checks are 1. */
    private fun exitCodeOf(rows: List<CheckResult>): Int {
        val failed = rows.filter { it.status == CheckStatus.FAILED }.map { it.name }
        return when {
            rows.all { it.status == CheckStatus.OK } -> ExitCodes.OK
            Doctor.CONFIGURATION in failed || Doctor.POLICY in failed -> ExitCodes.CONFIG_OR_ABORTED
            else -> ExitCodes.FAILURE
        }
    }

    private fun symbol(status: CheckStatus): String =
        when (status) {
            CheckStatus.OK -> OK
            CheckStatus.FAILED -> FAILED
            CheckStatus.SKIPPED -> SKIPPED
        }

    private fun colored(cell: String): String =
        when (cell.trim()) {
            OK -> TextColors.green(cell)
            FAILED -> TextColors.red(cell)
            else -> TextColors.gray(cell)
        }

    private companion object {
        const val OK = "✓"
        const val FAILED = "✗"
        const val SKIPPED = "–"
    }
}
