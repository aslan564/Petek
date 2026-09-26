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
import az.petek.app.diagnostics.HttpProbe
import az.petek.app.diagnostics.ProbeReport
import az.petek.app.diagnostics.ProbeReportWriter
import az.petek.app.diagnostics.TargetCapabilities
import az.petek.app.diagnostics.TargetProbe
import az.petek.app.diagnostics.TestApiProbe
import com.github.ajalt.clikt.core.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `petek probe [--url <url>]`: checks a target against docs/TARGET_CONTRACT.md anonymously (see [TargetProbe]),
 * prints a per-page summary and writes `<evidence>/probe/report.md`. Exit code 0 when the target is ready for
 * Pətək, 1 when something is missing. The test token is only sent to `PETEK_TARGET`, never to another `--url`.
 */
class ProbeCommand : PetekSubcommand("probe") {
    private val url by urlOption("site to probe (default: PETEK_TARGET)")

    override fun help(context: Context): String = "Check which parts of the target contract a site offers (no form is submitted)."

    override suspend fun execute(): Int =
        withContainer { container ->
            val config = container.config
            val target = url ?: config.target
            TargetGuard.requireAllowed(config.targetPolicy, target)
            val isConfiguredTarget = TargetGuard.origin(target) == TargetGuard.origin(config.target)
            HttpProbe().use { http ->
                val probe =
                    TargetProbe(
                        browser = container.browserEngine,
                        browserConfig = container.browserConfig(),
                        http = http,
                        oracle = if (isConfiguredTarget) container.oracle else null,
                        tokenNotSentReason = "--url is not PETEK_TARGET, and the test token is only ever sent to PETEK_TARGET",
                        observationWindow = session.runtime.observationWindow,
                    )
                val report = probe.probe(target)
                val directory = config.evidenceDir.resolve(PROBE_DIRECTORY)
                val capabilities = TargetCapabilities.of(report, config.mailSource.key)
                val file =
                    withContext(Dispatchers.IO) {
                        ProbeReportWriter.write(report, container.clock.now().wall, directory, capabilities)
                    }
                printSummary(report)
                echo("Capabilities: ${capabilities.summary()}")
                echo("Report: $file")
                if (report.ready) ExitCodes.OK else ExitCodes.FAILURE
            }
        }

    private fun printSummary(report: ProbeReport) {
        echo("Target ${PetekConfig.masked(report.target)}: ${if (report.ready) "ready" else "not ready"} for Pətək")
        val rows =
            report.pages.map { page ->
                val tolerated = if (page.elementsRequired || page.missing.isEmpty()) "" else " (not required: ${page.note})"
                val missing =
                    page.error?.let { "error: $it" }
                        ?: (page.missing.joinToString(", ") { ProbeReportWriter.testIdOf(it) }.ifEmpty { "-" } + tolerated)
                listOf(page.path, page.http.toString(), missing)
            }
        echo(TextTable.render(listOf("Page", "HTTP", "Missing data-testids"), rows))
        echo("Test API without token: ${report.testApi.anonymous}; with token: ${describe(report.testApi.withToken)}")
        val transports =
            report.realtime.transports
                .map { it.name.lowercase() }
                .sorted()
        echo("Real-time transport on the home page: ${transports.joinToString(", ").ifEmpty { "none detected" }}")
    }

    private fun describe(check: TestApiProbe.TokenCheck): String =
        when (check) {
            is TestApiProbe.TokenCheck.Answered -> "HTTP ${check.status}"
            is TestApiProbe.TokenCheck.Failed -> "failed (${check.error})"
            is TestApiProbe.TokenCheck.NotSent -> "not sent (${check.reason})"
        }

    companion object {
        const val PROBE_DIRECTORY = "probe"
    }
}
