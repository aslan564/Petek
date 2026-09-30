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

import az.petek.core.error.PetekException
import az.petek.core.ids.RunId
import az.petek.evidence.domain.RunRecord
import az.petek.reporting.application.CompareRunsUseCase
import az.petek.reporting.application.CompareRunsUseCase.Baseline
import az.petek.reporting.domain.ComparisonRefusedException
import az.petek.reporting.domain.StepChange
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `petek compare <run_id|latest> [--baseline previous|<run_id>|<release>]`: a finished run against a baseline run of the
 * same scenario (the regression baseline, docs/PLAN.md Faza 14): what broke, what got fixed, what the site made slower.
 * The baseline is the latest earlier run of the scenario, a named run, or the latest run of a release named with
 * `petek run --release`. Writes `report/compare.html` and `report/compare.md` beside the run's report. Exit code 0 when
 * nothing got worse, 1 when something did, 2 when the runs cannot be compared. Nothing touches the target.
 */
class CompareCommand : PetekSubcommand("compare") {
    private val run by argument("run_id", help = "the run to compare, e.g. run_0192…, or 'latest'")
    private val baseline by option(
        "--baseline",
        help = "what to compare with: 'previous' (default), a run id, or a release named with petek run --release",
    ).default(PREVIOUS)

    override fun help(context: Context): String =
        "Compare a run with an earlier run of the same scenario (another release): new failures, fixes, slower steps."

    override fun exitCodeFor(error: Exception): Int =
        if (error is ComparisonRefusedException) ExitCodes.CONFIG_OR_ABORTED else super.exitCodeFor(error)

    override suspend fun execute(): Int =
        withContainer { container ->
            val runId =
                if (run.equals(ReportCommand.LATEST, ignoreCase = true)) {
                    container.runs.latest()?.runId ?: throw PetekException("No run is recorded in ${container.config.dbPath} yet")
                } else {
                    RunId(run.trim())
                }
            val result = container.compareRuns.compare(runId, choice(baseline))
            if (json) emitJson(document(result)) else print(result)
            if (result.comparison.regressed) ExitCodes.FAILURE else ExitCodes.OK
        }

    private fun choice(text: String): Baseline {
        val value = text.trim()
        return when {
            value.equals(PREVIOUS, ignoreCase = true) -> Baseline.Previous
            RUN_ID.matches(value) -> Baseline.Run(RunId(value))
            else -> Baseline.Release(value)
        }
    }

    private fun print(result: CompareRunsUseCase.Result) {
        val comparison = result.comparison
        echo("Comparing ${label(comparison.current)} with ${label(comparison.baseline)}, scenario '${comparison.current.campaignName}'.")
        if (comparison.scenarioChanged) echo("The scenario file changed between them: steps are matched by their ids.")
        echo("Worse: ${if (comparison.regressed) "yes" else "no"}")
        line("New failures", comparison.steps.filter { it.change == StepChange.NEW_FAILURE }.map { it.scenarioStep })
        line("Fixed", comparison.fixed.map { it.scenarioStep })
        line("Still failing", comparison.steps.filter { it.change == StepChange.STILL_FAILING }.map { it.scenarioStep })
        line(
            "Slower",
            comparison.slowerSteps.map { "${it.scenarioStep} ${it.beforeMs} ms → ${it.afterMs} ms" } +
                comparison.slowerDeliveries.map { "${it.event} p95 ${it.beforeP95Ms} ms → ${it.afterP95Ms} ms" },
        )
        line("Not comparable", comparison.steps.filter { it.change == StepChange.NOT_COMPARABLE }.map { it.scenarioStep })
        result.files.forEach { echo("Written: $it") }
    }

    private fun line(
        title: String,
        items: List<String>,
    ) = echo("$title (${items.size})" + if (items.isEmpty()) "" else ": ${items.joinToString("; ")}")

    private fun label(run: RunRecord): String = run.runId.value + (run.release?.let { " (release $it)" } ?: "")

    private fun document(result: CompareRunsUseCase.Result): JsonObject {
        val comparison = result.comparison
        return buildJsonObject {
            put("runId", comparison.current.runId.value)
            put("release", comparison.current.release)
            put("baseline", comparison.baseline.runId.value)
            put("baselineRelease", comparison.baseline.release)
            put("scenario", comparison.current.campaignName)
            put("scenarioChanged", comparison.scenarioChanged)
            put("regressed", comparison.regressed)
            putJsonArray("steps") {
                comparison.steps.forEach { step ->
                    add(
                        buildJsonObject {
                            put("step", step.scenarioStep)
                            put("change", step.change.name)
                            put("beforeMs", step.beforeMs)
                            put("afterMs", step.afterMs)
                            put("speed", step.speed?.name)
                        },
                    )
                }
            }
            putJsonArray("deliveries") {
                comparison.deliveries.forEach { delivery ->
                    add(
                        buildJsonObject {
                            put("event", delivery.event)
                            put("beforeP50Ms", delivery.beforeP50Ms)
                            put("beforeP95Ms", delivery.beforeP95Ms)
                            put("afterP50Ms", delivery.afterP50Ms)
                            put("afterP95Ms", delivery.afterP95Ms)
                            put("speed", delivery.speed?.name)
                        },
                    )
                }
            }
            putJsonObject("thresholds") {
                put("ratio", comparison.thresholds.ratio)
                put("atLeastMs", comparison.thresholds.atLeast.toMillis())
            }
            putJsonArray("files") { result.files.forEach { add(JsonPrimitive(it.toAbsolutePath().toString())) } }
        }
    }

    private companion object {
        const val PREVIOUS = "previous"
        val RUN_ID = Regex("run_[A-Za-z0-9_-]+")
    }
}
