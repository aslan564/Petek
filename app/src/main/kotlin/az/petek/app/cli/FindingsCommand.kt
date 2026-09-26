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
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.reporting.domain.FindingBundle
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `petek findings <run_id|latest> [--finding <id>]`: the findings of a stored run as root-cause bundles (Faza 11), the
 * same object MCP `get_finding_bundle` returns: finding, sources A/B/C, evidence tier, step and evidence with text
 * evidence inline. With `--json` it is one JSON document for a coding AI (`petek findings latest --json`); otherwise a
 * short list. Nothing touches the target.
 */
class FindingsCommand : PetekSubcommand("findings") {
    private val run by argument("run_id", help = "a run id such as run_0192…, or 'latest'")
    private val finding by option("--finding", help = "only this finding id", metavar = "ID")

    override fun help(context: Context): String = "Print a run's findings with their step and evidence (root-cause bundles)."

    override suspend fun execute(): Int =
        withContainer { container ->
            val runId =
                if (run.equals(ReportCommand.LATEST, ignoreCase = true)) {
                    container.runs.latest()?.runId ?: throw PetekException("No run is recorded in ${container.config.dbPath} yet")
                } else {
                    RunId(run.trim())
                }
            val bundles = container.findingBundles.bundles(runId, finding?.let(::FindingId))
            if (json) {
                emitJson(
                    buildJsonObject {
                        put("runId", runId.value)
                        put("findings", JsonArray(bundles.map(::bundleJson)))
                    },
                )
                return@withContainer ExitCodes.OK
            }
            if (bundles.isEmpty()) echo("Run $runId has no findings.")
            bundles.forEach { bundle ->
                val f = bundle.finding
                echo("${f.findingId} ${f.findingClass} [${f.evidenceTier}] ${f.scenarioStep} ${f.agentId ?: ""}: ${f.note}")
                bundle.evidence.forEach { echo("  ${it.type.name.lowercase()}: ${it.path}") }
            }
            ExitCodes.OK
        }

    companion object {
        fun bundleJson(bundle: FindingBundle): JsonObject {
            val finding = bundle.finding
            return buildJsonObject {
                put("findingId", finding.findingId.value)
                put("class", finding.findingClass.name)
                put("evidenceTier", finding.evidenceTier.name)
                put("step", finding.scenarioStep)
                put("agentId", finding.agentId?.value)
                put("a", finding.a)
                put("b", finding.b)
                put("c", finding.c)
                put("note", finding.note)
                put("target", bundle.target)
                bundle.step?.let { step ->
                    putJsonObject("stepRecord") {
                        put("stepId", step.stepId.value)
                        put("action", step.action)
                        put("kind", step.kind.name)
                        put("status", step.status.name)
                        put("detail", step.detail)
                        put("startedAt", step.startedAt.toString())
                        put("durationMs", step.durationMs)
                        put("correlationId", step.correlationId.value)
                    }
                }
                putJsonArray("evidence") {
                    bundle.evidence.forEach { evidence ->
                        addJsonObject {
                            put("artifactId", evidence.artifactId)
                            put("type", evidence.type.name)
                            put("path", evidence.path)
                            put("text", evidence.text)
                        }
                    }
                }
                putJsonArray("artifactIds") { finding.artifactIds.forEach { add(it.value) } }
                putJsonArray("serverLog") { bundle.serverLog.forEach { add(it) } }
            }
        }
    }
}
