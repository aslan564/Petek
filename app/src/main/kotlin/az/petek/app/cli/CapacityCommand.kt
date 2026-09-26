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
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.domain.Bytes
import az.petek.capacity.domain.CapacityRecommendation
import az.petek.capacity.infrastructure.BrowserSessionCostProbe
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo

/**
 * `petek capacity [--measure N] [--url <url>]`: how many testers this machine can run at once, as advice and never
 * as a limit (see [RecommendCapacityUseCase]). Without `--measure` the answer is instant and uses the documented
 * per-session estimates; with `--measure N` it opens N real Chromium sessions on the target (or `--url`), measures
 * what they cost on this machine and recommends from that. Exit code 0; 2 when the page to measure on is refused
 * by the target policy.
 */
class CapacityCommand : PetekSubcommand("capacity") {
    private val measure by option(
        "--measure",
        help = "open N real browser sessions and measure their cost instead of using the estimates",
        metavar = "N",
    ).int().restrictTo(min = 1)
    private val url by urlOption("page the measured sessions open (default: PETEK_TARGET)")

    override fun help(context: Context): String = "Recommend how many testers this machine can run at once (advice, not a limit)."

    override suspend fun execute(): Int =
        withContainer { container ->
            val browserConfig = container.browserConfig()
            val measurement =
                measure?.let { sessions ->
                    val page = url ?: container.config.target
                    TargetGuard.requireAllowed(container.config.targetPolicy, page)
                    echo("Measuring $sessions real browser sessions on ${PetekConfig.masked(page)}…")
                    RecommendCapacityUseCase.Measurement(sessions = sessions, url = page)
                }
            val advice =
                RecommendCapacityUseCase(
                    hostProbe = SystemHostResourceProbe(),
                    costProbe = BrowserSessionCostProbe(container.browserEngine, browserConfig),
                ).execute(contextsPerBrowser = browserConfig.contextsPerBrowser, measurement = measurement)
            echo("Recommended maximum: ${advice.maxTesters} testers at once (limited by ${advice.limitingFactor.name.lowercase()}).")
            echo(TextTable.render(listOf("Figure", "Value"), rows(advice)))
            echo("")
            advice.notes.forEach { echo("- $it") }
            ExitCodes.OK
        }

    private fun rows(advice: CapacityRecommendation): List<List<String>> =
        listOf(
            listOf("Memory total", Bytes.format(advice.host.totalMemoryBytes)),
            listOf("Memory available", Bytes.format(advice.host.availableMemoryBytes)),
            listOf("Reserved for the system", Bytes.format(advice.reserveBytes)),
            listOf("CPU cores", advice.host.cpuCores.toString()),
            listOf("Per session", Bytes.format(advice.perSession.bytesPerSession) + source(advice)),
            listOf(
                "Per shared browser",
                Bytes.format(advice.perSession.bytesPerBrowser) + " (up to ${advice.contextsPerBrowser} sessions)",
            ),
            listOf("Memory allows", "${advice.memoryBound} testers"),
            listOf("CPU allows", "${advice.cpuBound} testers"),
        )

    private fun source(advice: CapacityRecommendation): String = if (advice.perSession.measured) " (measured)" else " (estimate)"
}
