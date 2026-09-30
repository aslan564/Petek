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

import az.petek.app.panel.PanelCore
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.TestFlowView
import az.petek.dashboard.domain.TestStage
import az.petek.evidence.domain.RunResult
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.milliseconds

/**
 * `petek test [--target <url>]`: the main path (Faza 25.3), the panel's "Test et" without the panel. The explorer learns
 * the site, the scenario is drafted only from what it found, approved and run with the tester agents; nobody writes a
 * scenario file. The parts are the panel's own operations over the same object graph, so their rules hold here too
 * (the target policy, the proof of ownership before anything is written, a site that must answer). Progress goes to
 * stdout, the report path at the end. Exit code 0 when the run PASSED, 1 when it FAILED, 3 when it did not pass only
 * because checks could not be decided, 2 when the test stopped before a run ended (no site, a refused or silent site,
 * an exploration without a model, a draft that does not validate, a run that was refused or ABORTED).
 */
class TestCommand : PetekSubcommand(NAME) {
    private val target by option("--target", help = "the site to test (default: PETEK_TARGET)", metavar = "URL")
    private val testers by option("--testers", help = "tester agents that run the drafted scenario (default 6)", metavar = "N")
        .int()
        .restrictTo(min = 1, max = PanelInstructions.MAX_TESTERS)
        .default(DEFAULT_TESTERS)
    private val instructions by option("--instructions", help = "what to test, what matters, what to avoid (free text)").default("")
    private val trialTouch by option(
        "--allow-writes",
        help = "let the explorer submit each create form once, so the draft can check what the site's test API saw (test data only)",
    ).flag()
    private val maxPages by option("--max-pages", help = "the explorer's page budget (default 40)", metavar = "N")
        .int()
        .restrictTo(min = 1, max = PanelBudget.MAX_PAGES)
        .default(DEFAULT_PAGES)
    private val maxMinutes by option("--max-minutes", help = "the explorer's time budget in minutes (default 10)", metavar = "N")
        .int()
        .restrictTo(min = 1, max = PanelBudget.MAX_MINUTES)
        .default(DEFAULT_MINUTES)

    override fun help(context: Context): String =
        "Test the site in one go: explore it, draft the scenario from what was found, approve and run it."

    /** Nothing a test command fails with is a test finding: a test that could not start counts as aborted. */
    override fun exitCodeFor(error: Exception): Int = ExitCodes.CONFIG_OR_ABORTED

    override suspend fun execute(): Int {
        if (!session.namesSite) {
            echo(PanelCommand.NO_TARGET, err = true)
            return ExitCodes.CONFIG_OR_ABORTED
        }
        val config = session.loadConfig()
        session.configureLogging(config, interactive = terminal.terminalInfo.outputInteractive)
        val site = target?.trim()?.takeIf { it.isNotEmpty() } ?: config.target.toString()
        TargetGuard.requireAllowed(config.targetPolicy, parse(site))
        return PanelCore
            .start(
                config = config,
                containers = session.runtime.panelContainers,
                workingDirectory = session.configurationDirectory,
                capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                configurationFile = session.configurationFile,
            ).use { core -> test(core.backend, site) }
    }

    private suspend fun test(
        backend: PanelBackend,
        site: String,
    ): Int {
        val form =
            PanelInstructions(
                target = site,
                instructions = instructions,
                testers = testers,
                budget = PanelBudget(maxMinutes = maxMinutes, maxStepsPerAgent = DEFAULT_STEPS, maxPages = maxPages),
                allowWrites = trialTouch,
            )
        var shown = backend.startTest(form)
        say("Testing $site with $testers tester(s): the explorer learns the site first (at most $maxPages pages, $maxMinutes min).")
        while (!shown.stage.isFinal) {
            delay(POLL)
            val now = backend.testFlow() ?: break
            progress(shown, now)
            shown = now
        }
        val report =
            shown.runId
                ?.let { backend.reportDirectory(it) }
                ?.resolve(RunCommand.HTML_REPORT)
                ?.toAbsolutePath()
        val code = exitCodeOf(shown)
        if (json) {
            emitJson(
                buildJsonObject {
                    put("exitCode", code)
                    put("target", shown.target)
                    put("stage", shown.stage.name)
                    put("result", shown.result?.name)
                    put("explorationId", shown.explorationId)
                    put("scenarioId", shown.scenarioId)
                    put("runId", shown.runId?.value)
                    put("note", shown.note)
                    put("report", report?.toString())
                    put("nextScenarioId", shown.nextScenarioId)
                },
            )
        } else {
            shown.note?.let { echo(it, err = code == ExitCodes.CONFIG_OR_ABORTED) }
            report?.let { echo("  Report: $it") }
            shown.nextScenarioId?.let { echo("  Next run's scenario (a draft to approve): $it") }
        }
        return code
    }

    /** One line for each part the test finished or started since [before] (polling may see two at once). */
    private fun progress(
        before: TestFlowView,
        now: TestFlowView,
    ) {
        if (before.stage == TestStage.EXPLORING && now.stage != TestStage.EXPLORING && now.explorationId != null) {
            say("Exploration ${now.explorationId} ended.")
        }
        if (now.scenarioId != null && now.scenarioId != before.scenarioId) {
            say("Scenario ${now.scenarioId} drafted from what the explorer found.")
        }
        if (now.runId != null && now.runId != before.runId) {
            say("Run ${now.runId} is going with $testers tester(s); the report follows when it ends.")
        }
    }

    private fun say(line: String) {
        if (!json) echo(line)
    }

    private fun parse(site: String): URI =
        try {
            URI(site)
        } catch (_: URISyntaxException) {
            throw TargetRefusedException("--target is not a URL: $site")
        }

    companion object {
        const val NAME = "test"
        private const val DEFAULT_TESTERS = 6
        private const val DEFAULT_PAGES = 40
        private const val DEFAULT_MINUTES = 10
        private const val DEFAULT_STEPS = 40
        private val POLL = 500.milliseconds

        /**
         * 0 when the run PASSED, 1 when it FAILED, 3 when it did not pass only because checks could not be decided, 2 when
         * the test stopped before a run ended.
         */
        fun exitCodeOf(view: TestFlowView): Int =
            when {
                view.stage == TestStage.FINISHED && view.result == RunResult.PASSED -> ExitCodes.OK
                view.stage == TestStage.FINISHED && view.result == RunResult.FAILED && view.undecided -> ExitCodes.INCONCLUSIVE
                view.stage == TestStage.FINISHED && view.result == RunResult.FAILED -> ExitCodes.FAILURE
                else -> ExitCodes.CONFIG_OR_ABORTED
            }
    }
}
