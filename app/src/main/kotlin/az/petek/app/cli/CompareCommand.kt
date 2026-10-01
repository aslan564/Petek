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

import az.petek.app.runs.LookSummaries
import az.petek.app.runs.SlowerLines
import az.petek.core.error.PetekException
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ReleaseNames
import az.petek.evidence.domain.RunRecord
import az.petek.reporting.application.CompareRunsUseCase
import az.petek.reporting.application.CompareRunsUseCase.Baseline
import az.petek.reporting.domain.ComparisonRefusedException
import az.petek.reporting.domain.RunNotFoundException
import az.petek.reporting.domain.StepChange
import az.petek.reporting.domain.visual.LookSide
import az.petek.reporting.domain.visual.VisualGate
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

/**
 * `petek compare <run_id|latest> [--baseline previous|<run_id>|<release>] [--visual report|fail]`: a finished run against
 * a baseline run of the same scenario (the regression baseline, docs/PLAN.md Faza 14): what broke, what got fixed, what
 * the site made slower, and which pages look different (docs/adr/0014; `--visual fail` counts those as worse).
 * The baseline is the latest earlier run of the scenario, a named run, or the latest run of a release named with
 * `petek run --release`. Writes `report/compare-<baseline run>.html` and `.md` beside the run's report. Exit code 0 when
 * nothing got worse, 1 when something did, 2 when nothing was compared (the runs cannot be compared, or a run is not
 * recorded), so a CI job never reads a typo as a regression. Nothing touches the target.
 */
class CompareCommand : PetekSubcommand("compare") {
    private val run by argument("run_id", help = "the run to compare, e.g. run_0192…, or 'latest'")
    private val baseline by option(
        "--baseline",
        help = "what to compare with: 'previous' (default), a run id, or a release named with petek run --release",
    ).default(ReleaseNames.PREVIOUS)
    private val visual by option(
        "--visual",
        help = "a page that looks different: 'report' (default) shows it, 'fail' also counts it as worse (exit code 1)",
    ).choice("report", "fail").default("report")

    override fun help(context: Context): String =
        "Compare a run with an earlier run of the same scenario (another release): new failures, fixes, slower steps."

    override fun exitCodeFor(error: Exception): Int =
        when (error) {
            is ComparisonRefusedException, is RunNotFoundException, is NoRunRecordedException -> ExitCodes.CONFIG_OR_ABORTED
            else -> super.exitCodeFor(error)
        }

    override suspend fun execute(): Int =
        withContainer { container ->
            val runId =
                if (run.equals(ReportCommand.LATEST, ignoreCase = true)) {
                    container.runs.latest()?.runId ?: throw NoRunRecordedException("No run is recorded in ${container.config.dbPath} yet")
                } else {
                    RunId(run.trim())
                }
            val gate = if (visual == "fail") VisualGate.FAIL else VisualGate.REPORT
            val result = container.compareRuns.compare(runId, choice(baseline), gate)
            if (json) emitJson(document(result, container.artifacts)) else print(result)
            if (result.comparison.regressed) ExitCodes.FAILURE else ExitCodes.OK
        }

    private fun choice(text: String): Baseline {
        val value = text.trim()
        return when {
            value.equals(ReleaseNames.PREVIOUS, ignoreCase = true) -> Baseline.Previous
            ReleaseNames.RUN_ID.matches(value) -> Baseline.Run(RunId(value))
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
        line("Slower", SlowerLines.CLI.of(comparison))
        line("Not comparable", comparison.steps.filter { it.change == StepChange.NOT_COMPARABLE }.map { it.scenarioStep })
        if (comparison.looks.isNotEmpty()) {
            echo("Visual gate: ${comparison.visualGate.name.lowercase()}")
            line("Looks changed", comparison.changedLooks.map(LookSummaries::changed))
            line("Looks not comparable", comparison.incomparableLooks.map(LookSummaries::notComparable))
        }
        result.files.forEach { echo("Written: $it") }
        result.visualDirectory?.let { echo("Pictures: $it") }
    }

    private fun line(
        title: String,
        items: List<String>,
    ) = echo("$title (${items.size})" + if (items.isEmpty()) "" else ": ${items.joinToString("; ")}")

    private fun label(run: RunRecord): String = run.runId.value + (run.release?.let { " (release $it)" } ?: "")

    private fun document(
        result: CompareRunsUseCase.Result,
        artifacts: ArtifactStore,
    ): JsonObject {
        val comparison = result.comparison
        val reportDirectory =
            result.files
                .firstOrNull()
                ?.toAbsolutePath()
                ?.parent
        val evidenceRoot = artifacts.runDirectory(comparison.current.runId).toAbsolutePath().parent

        fun reportFile(link: String?): String? = link?.let { reportDirectory?.resolve(it)?.normalize()?.toString() }
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
            putJsonArray("pages") {
                comparison.pages.forEach { page ->
                    add(
                        buildJsonObject {
                            put("page", page.page)
                            put("device", page.device)
                            put("beforeLoadMs", page.beforeLoadMs)
                            put("afterLoadMs", page.afterLoadMs)
                            put("beforeLargestPaintMs", page.beforePaintMs)
                            put("afterLargestPaintMs", page.afterPaintMs)
                            put("beforeLayoutShift", page.beforeShift)
                            put("afterLayoutShift", page.afterShift)
                            put("speed", page.speed?.name)
                            put("layoutShiftGrew", page.shiftGrew)
                        },
                    )
                }
            }
            put("visualGate", comparison.visualGate.name.lowercase())
            putJsonArray("looks") {
                comparison.looks.forEach { look ->
                    add(
                        buildJsonObject {
                            put("index", look.index)
                            put("step", look.key.scenarioStep)
                            put("page", look.key.page)
                            put("device", look.key.device)
                            put("change", look.change.name)
                            put("reason", look.reason?.name)
                            putJsonArray("facts") {
                                look.facts.forEach { fact ->
                                    add(
                                        buildJsonObject {
                                            put("kind", fact.kind.name)
                                            put("before", fact.before)
                                            put("after", fact.after)
                                        },
                                    )
                                }
                            }
                            putJsonArray("regions") {
                                look.regions.forEach { region ->
                                    add(
                                        buildJsonObject {
                                            put("x", region.box.x)
                                            put("y", region.box.y)
                                            put("width", region.box.width)
                                            put("height", region.box.height)
                                            put("pixels", region.pixels)
                                            put("runContent", region.runContent)
                                        },
                                    )
                                }
                            }
                            putJsonArray("bands") {
                                look.bands.forEach { band ->
                                    add(
                                        buildJsonObject {
                                            put("kind", band.kind.name)
                                            put("y", band.y)
                                            put("height", band.height)
                                            put("at", band.at)
                                            put("counted", band.counted)
                                        },
                                    )
                                }
                            }
                            put("changedPixels", look.changedPixels)
                            put("comparedPixels", look.comparedPixels)
                            putJsonObject("ignored") { look.ignored.forEach { (by, pixels) -> put(by.name.lowercase(), pixels) } }
                            look.before?.let { side -> put("before", side(side, evidenceRoot)) }
                            look.after?.let { side -> put("after", side(side, evidenceRoot)) }
                            put("overlay", reportFile(look.files?.overlay))
                            putJsonArray("crops") {
                                look.files?.crops?.forEach { crop ->
                                    add(
                                        buildJsonObject {
                                            put("before", reportFile(crop.before))
                                            put("after", reportFile(crop.after))
                                            put("diff", reportFile(crop.diff))
                                        },
                                    )
                                }
                            }
                            putJsonArray("suggestions") { look.suggestions.forEach { add(JsonPrimitive(it)) } }
                        },
                    )
                }
            }
            putJsonObject("thresholds") {
                put("ratio", comparison.thresholds.ratio)
                put("atLeastMs", comparison.thresholds.atLeast.toMillis())
                comparison.visualThresholds?.let { visual ->
                    putJsonObject("visual") {
                        put("colorDelta", visual.colorDelta)
                        put("shiftRadius", visual.shiftRadius)
                        put("cell", visual.cell)
                        put("regionCells", visual.regionCells)
                        put("bandRows", visual.bandRows)
                    }
                }
            }
            putJsonArray("files") { result.files.forEach { add(JsonPrimitive(it.toAbsolutePath().toString())) } }
            put("visualDirectory", result.visualDirectory?.toAbsolutePath()?.toString())
        }
    }

    /** One side of a look: whose capture it is, and its frame by absolute path and verified hash. */
    private fun side(
        side: LookSide,
        evidenceRoot: Path,
    ): JsonObject =
        buildJsonObject {
            put("runId", side.runId.value)
            put("agentId", side.agentId.value)
            put("stepId", side.stepId.value)
            put("artifactId", side.artifactId.value)
            put("path", evidenceRoot.resolve(side.relativePath).normalize().toString())
            put("sha256", side.sha256)
            put("verified", side.verified)
            put("renderer", side.renderer)
            put("samples", side.samples)
        }

    private class NoRunRecordedException(
        message: String,
    ) : PetekException(message)
}
