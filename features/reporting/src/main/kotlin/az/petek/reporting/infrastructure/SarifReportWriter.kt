/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

/**
 * The run's findings as SARIF 2.1.0 (`report/findings.sarif`, Faza 12 CI mode and Faza 20): one result per finding,
 * the finding class as the rule, the A/B/C sources, evidence tier and agent as properties, and the step as a logical
 * location; the first evidence file (relative to the report) is the physical location, so code-scanning viewers can
 * open the screenshot or the oracle answer.
 */
class SarifReportWriter : ReportWriter {
    override val fileName: String = "findings.sarif"

    override fun write(
        model: ReportModel,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName, render(model))

    fun render(model: ReportModel): String = JSON.encodeToString(JsonObject.serializer(), document(model))

    private fun document(model: ReportModel): JsonObject =
        buildJsonObject {
            put("\$schema", SCHEMA)
            put("version", "2.1.0")
            putJsonArray("runs") {
                addJsonObject {
                    putJsonObject("tool") {
                        putJsonObject("driver") {
                            put("name", "Pətək")
                            put("informationUri", "https://github.com/aslan564/Petek")
                            putJsonArray("rules") {
                                FindingClass.entries.forEach { rule ->
                                    addJsonObject {
                                        put("id", rule.name)
                                        put("name", rule.name.lowercase())
                                        putJsonObject("shortDescription") { put("text", ReportFormat.findingClass(rule)) }
                                        putJsonObject("defaultConfiguration") { put("level", level(rule)) }
                                    }
                                }
                            }
                        }
                    }
                    putJsonObject("automationDetails") { put("id", "petek/${model.run.campaignName}/${model.run.runId.value}") }
                    putJsonObject("properties") {
                        put("target", model.run.target)
                        put("result", model.run.result.name)
                        put("workspaceId", model.workspaceId.value)
                    }
                    putJsonArray("results") { model.findings.forEach { add(result(model, it)) } }
                }
            }
        }

    private fun result(
        model: ReportModel,
        finding: FindingRecord,
    ): JsonObject =
        buildJsonObject {
            put("ruleId", finding.findingClass.name)
            put("level", level(finding.findingClass))
            putJsonObject("message") { put("text", finding.note) }
            putJsonArray("locations") {
                addJsonObject {
                    val evidence = finding.artifactIds.firstNotNullOfOrNull { ReportFormat.safeLink(model.artifactLinks[it.value]) }
                    if (evidence != null) {
                        putJsonObject("physicalLocation") { putJsonObject("artifactLocation") { put("uri", evidence) } }
                    }
                    putJsonArray("logicalLocations") {
                        addJsonObject {
                            put("name", finding.scenarioStep)
                            put("kind", "function")
                        }
                    }
                }
            }
            putJsonObject(
                "partialFingerprints",
            ) { put("petekFinding/v1", "${finding.findingClass}:${finding.scenarioStep}:${finding.agentId ?: "-"}") }
            putJsonObject("properties") {
                put("findingId", finding.findingId.value)
                put("runId", finding.runId.value)
                put("agentId", finding.agentId?.value)
                put("evidenceTier", finding.evidenceTier.name)
                put("a", finding.a)
                put("b", finding.b)
                put("c", finding.c)
                putJsonArray("evidence") {
                    finding.artifactIds.forEach { id ->
                        ReportFormat.safeLink(model.artifactLinks[id.value])?.let(::add)
                    }
                }
            }
        }

    private fun level(findingClass: FindingClass): String =
        when (findingClass) {
            FindingClass.BACKEND, FindingClass.DELIVERY_UI -> "error"
            FindingClass.INVESTIGATE, FindingClass.AGENT_FAILURE -> "warning"
            FindingClass.FLAKY -> "note"
        }

    private companion object {
        const val SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
        val JSON = Json { prettyPrint = true }
    }
}
