/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportWriter
import az.petek.reporting.domain.StepRow
import java.nio.file.Path

/**
 * The run as JUnit XML (`report/junit.xml`, Faza 12 CI mode): one test case per step row (scenario step × agent), so CI
 * servers show Pətək's steps like unit tests. A lost race and an expected refusal are passes (the step asked for them),
 * a skipped or blocked step is `<skipped>`, a failed or errored one a `<failure>` with its detail. Findings go to SARIF.
 */
class JUnitReportWriter : ReportWriter {
    override val fileName: String = "junit.xml"

    override fun write(
        model: ReportModel,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName, render(model))

    fun render(model: ReportModel): String {
        val rows = model.steps
        val failures = rows.count { failed(it) }
        val skipped = rows.count { it.status in SKIPPED_STATUSES }
        val seconds = model.summary.durationMs / MILLIS_PER_SECOND
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<testsuites name=\"").append(xml("Pətək")).append("\" tests=\"").append(rows.size)
            append("\" failures=\"")
                .append(failures)
                .append("\" time=\"")
                .append(seconds)
                .append("\">\n")
            append("  <testsuite name=\"").append(xml(model.run.campaignName)).append("\" tests=\"").append(rows.size)
            append("\" failures=\"").append(failures).append("\" errors=\"0\" skipped=\"").append(skipped)
            append("\" time=\"")
                .append(seconds)
                .append("\" timestamp=\"")
                .append(model.run.startedAt)
                .append("\">\n")
            append("    <properties>\n")
            property("run_id", model.run.runId.value)
            property("target", model.run.target)
            property("workspace_id", model.workspaceId.value)
            property("result", model.run.result.name)
            property("findings", model.findings.size.toString())
            append("    </properties>\n")
            rows.forEach { row -> testCase(model, row) }
            append("  </testsuite>\n</testsuites>\n")
        }
    }

    private fun StringBuilder.property(
        name: String,
        value: String,
    ) {
        append("      <property name=\"")
            .append(xml(name))
            .append("\" value=\"")
            .append(xml(value))
            .append("\"/>\n")
    }

    private fun StringBuilder.testCase(
        model: ReportModel,
        row: StepRow,
    ) {
        val who = ReportFormat.agent(row.agentId, row.agentName)
        append("    <testcase classname=\"").append(xml("${model.run.campaignName}.${row.scenarioStep}")).append("\" name=\"")
        append(xml("${row.scenarioStep} · $who")).append("\" time=\"").append(row.durationMs / MILLIS_PER_SECOND).append("\"")
        when {
            failed(row) -> {
                append(">\n      <failure message=\"").append(xml(row.detail ?: row.status)).append("\" type=\"").append(row.status)
                append("\">").append(xml(row.detail ?: row.status)).append("</failure>\n    </testcase>\n")
            }

            row.status in SKIPPED_STATUSES -> {
                append(">\n      <skipped message=\"").append(xml(row.detail ?: row.status)).append("\"/>\n    </testcase>\n")
            }

            else -> {
                append("/>\n")
            }
        }
    }

    private fun failed(row: StepRow): Boolean = row.status in FAILED_STATUSES && !row.lostRace && !row.refused

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        val FAILED_STATUSES = setOf("FAILED", "ERROR")
        val SKIPPED_STATUSES = setOf("SKIPPED", "BLOCKED")
        private val INVALID_XML = Regex("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]")

        fun xml(text: String): String =
            INVALID_XML
                .replace(text, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
    }
}
