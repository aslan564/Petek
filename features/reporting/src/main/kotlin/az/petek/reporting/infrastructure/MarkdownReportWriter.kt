/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.evidence.domain.FindingRecord
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportWriter
import az.petek.reporting.infrastructure.ReportFormat.NONE
import java.nio.file.Path

/**
 * Writes `report.md`: the same sections as the HTML report, readable raw in a terminal and rendered on a code host.
 * Every value that came from the target, the LLM or the scenario is escaped, so it can neither break a table nor
 * inject HTML or links into a rendered view. Public because the composition root assembles the writer list.
 */
class MarkdownReportWriter : ReportWriter {
    override val fileName: String = "report.md"

    override fun write(
        model: ReportModel,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName, render(model))

    /** The Markdown document for [model]. */
    fun render(model: ReportModel): String =
        buildString {
            header(model)
            summary(model)
            findings(model)
            steps(model)
            latency(model)
            stability(model)
            failedAgents(model)
            usage(model)
        }

    private fun StringBuilder.header(model: ReportModel) {
        val run = model.run
        line("# ${md(ReportFormat.title(model))}")
        line()
        table(
            listOf("", ""),
            listOfNotNull(
                listOf("Run", md(run.runId.value)),
                listOf("Hədəf", md(run.target)),
                listOf("Başladı", ReportFormat.instant(run.startedAt)),
                listOf("Bitdi", ReportFormat.instant(run.endedAt)),
                listOf("Nəticə", ReportFormat.runResult(run.result)),
                listOf("Seed", run.seed.toString()),
                run.repeatGroup?.let { listOf("Təkrar qrupu", md(it) + (run.repeatIndex?.let { i -> " (#$i)" } ?: "")) },
            ),
        )
    }

    private fun StringBuilder.summary(model: ReportModel) {
        val s = model.summary
        section("Xülasə")
        table(
            listOf("Göstərici", "Dəyər"),
            listOf(
                listOf("Keçən addımlar", s.stepsPassed.toString()),
                listOf("Keçməyən addımlar", s.stepsFailed.toString()),
                listOf(
                    "Assertlər",
                    "${s.assertionsPassed} keçdi · ${s.assertionsFailed} keçmədi · ${s.assertionsSkipped} ötürüldü",
                ),
                listOf("Tapıntılar", model.findings.size.toString()),
                listOf("Agentlər", s.agents.toString()),
                listOf("Müddət", ReportFormat.duration(s.durationMs)),
                listOf(
                    "Tokenlər",
                    "giriş ${ReportFormat.count(s.inputTokens)} · çıxış ${ReportFormat.count(s.outputTokens)}",
                ),
                listOf("Xərc", ReportFormat.cost(s.costUsd)),
                listOf("Real-time nəqliyyat", s.realtimeTransports.joinToString(", ") { md(it) }.ifEmpty { NONE }),
            ),
        )
    }

    private fun StringBuilder.findings(model: ReportModel) {
        section("Tapıntılar (${model.findings.size})")
        if (model.findings.isEmpty()) return paragraph("Tapıntı yoxdur: bütün mənbələr uyğun gəlir.")
        model.findings.forEachIndexed { i, finding -> finding(model, i + 1, finding) }
    }

    private fun StringBuilder.finding(
        model: ReportModel,
        number: Int,
        finding: FindingRecord,
    ) {
        val agent = finding.agentId?.value
        val who = ReportFormat.agent(agent, ReportFormat.agentName(model, agent))
        line("### $number. ${ReportFormat.findingClass(finding.findingClass)} · ${md(finding.scenarioStep)} · ${md(who)}")
        line()
        table(
            listOf("Mənbə", "Dəyər"),
            listOf(
                listOf("A: göndərən", md(finding.a ?: NONE)),
                listOf("B: alan", md(finding.b ?: NONE)),
                listOf("C: oracle", md(finding.c ?: NONE)),
            ),
        )
        paragraph("Qeyd: ${md(finding.note)}")
        val evidence = evidenceLinks(model, finding.artifactIds.map { it.value })
        paragraph("Sübut: ${evidence.ifEmpty { NONE }}")
    }

    private fun StringBuilder.steps(model: ReportModel) {
        section("Addımlar (${model.steps.size})")
        if (model.steps.isEmpty()) return paragraph("Addım qeydə alınmayıb.")
        table(
            listOf("Addım", "Agent", "Növ", "Nəticə", "Müddət", "Detal", "Screenshot"),
            model.steps.map { row ->
                listOf(
                    md(row.scenarioStep),
                    md(ReportFormat.agent(row.agentId, row.agentName)),
                    row.kind.lowercase(),
                    ReportFormat.stepStatus(row),
                    ReportFormat.duration(row.durationMs),
                    md(row.detail ?: NONE),
                    link(model, row.screenshot) ?: NONE,
                )
            },
        )
    }

    private fun StringBuilder.latency(model: ReportModel) {
        section("Real-time gecikmə")
        if (model.latency.isEmpty()) return paragraph("Real-time hadisəsi olmayıb.")
        table(
            listOf("Hadisə", "Alanlar", "Çatdı", "Orta", "p95", "Maks", "Çatmayanlar"),
            model.latency.map { stats ->
                listOf(
                    md(stats.event),
                    stats.receivers.toString(),
                    stats.received.toString(),
                    ReportFormat.latency(stats.avgMs),
                    ReportFormat.latency(stats.p95Ms),
                    ReportFormat.latency(stats.maxMs),
                    stats.missing.joinToString(", ") { md(it) }.ifEmpty { NONE },
                )
            },
        )
        model.latency.filter { it.perReceiverMs.isNotEmpty() }.forEach { stats ->
            val perReceiver = stats.perReceiverMs.entries.joinToString(", ") { (agent, ms) -> "${md(agent)} ${ReportFormat.latency(ms)}" }
            line("- **${md(stats.event)}**: $perReceiver")
        }
        line()
    }

    private fun StringBuilder.stability(model: ReportModel) {
        val rows = model.stability ?: return
        section("Stabillik")
        if (rows.isEmpty()) return paragraph("Təkrar qrupunda addım yoxdur.")
        table(
            listOf("Addım", "Run-lar", "Keçdi", "Keçmə faizi", "Vəziyyət"),
            rows.map { row ->
                listOf(
                    md(row.scenarioStep),
                    row.runs.toString(),
                    row.passed.toString(),
                    ReportFormat.percent(row.passRate),
                    ReportFormat.stability(row),
                )
            },
        )
    }

    private fun StringBuilder.failedAgents(model: ReportModel) {
        section("Uğursuz agentlər (${model.failedAgents.size})")
        if (model.failedAgents.isEmpty()) return paragraph("Bütün agentlər addımlarını tamamladı.")
        table(
            listOf("Agent", "Ad", "Addım", "Səbəb"),
            model.failedAgents.map { listOf(md(it.agentId), md(it.name), md(it.scenarioStep), md(it.reason)) },
        )
    }

    private fun StringBuilder.usage(model: ReportModel) {
        section("İstifadə: token və xərc")
        val s = model.summary
        val total =
            listOf(
                "**Cəmi**",
                ReportFormat.count(s.inputTokens),
                ReportFormat.count(s.outputTokens),
                ReportFormat.count(model.usage.sumOf { it.cacheReadTokens }),
                model.usage.sumOf { it.calls }.toString(),
                ReportFormat.cost(s.costUsd),
            )
        table(
            listOf("Agent", "Giriş", "Çıxış", "Keşdən oxunan", "Çağırışlar", "Xərc"),
            model.usage.sortedBy { it.agentId }.map { u ->
                listOf(
                    md(u.agentId.value),
                    ReportFormat.count(u.inputTokens),
                    ReportFormat.count(u.outputTokens),
                    ReportFormat.count(u.cacheReadTokens),
                    u.calls.toString(),
                    ReportFormat.cost(u.costUsd),
                )
            } + listOf(total),
        )
    }

    private fun evidenceLinks(
        model: ReportModel,
        artifactIds: List<String>,
    ): String = artifactIds.mapNotNull { link(model, it) }.joinToString(", ")

    private fun link(
        model: ReportModel,
        artifactId: String?,
    ): String? {
        val target = ReportFormat.safeLink(artifactId?.let { model.artifactLinks[it] }) ?: return null
        return "[${md(ReportFormat.fileLabel(target))}](${destination(target)})"
    }

    private fun StringBuilder.section(title: String) = paragraph("## $title")

    private fun StringBuilder.paragraph(text: String) {
        line(text)
        line()
    }

    private fun StringBuilder.table(
        header: List<String>,
        rows: List<List<String>>,
    ) {
        line(header.joinToString(" | ", "| ", " |"))
        line(header.joinToString(" | ", "| ", " |") { "---" })
        rows.forEach { line(it.joinToString(" | ", "| ", " |")) }
        line()
    }

    private fun StringBuilder.line(text: String = "") {
        append(text).append('\n')
    }

    private companion object {
        val MARKDOWN_SPECIAL = Regex("""[\\`*\[\]|<#]""")
        val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")
        val DESTINATION_SPECIAL = Regex("""[\\<>]""")

        /**
         * Escapes untrusted text for a single-line, mid-line Markdown context (table cell, heading, list item):
         * line breaks are flattened, `<` can open no tag, and link, emphasis, code and table syntax is neutral.
         * `>` stays readable because it only means something at the start of a line.
         */
        fun md(text: String): String = text.replace(LINE_BREAKS, " ").replace(MARKDOWN_SPECIAL) { "\\" + it.value }

        /** Link destinations in angle brackets tolerate spaces; `<`, `>` and `\` are escaped inside. */
        fun destination(path: String): String = "<" + path.replace(DESTINATION_SPECIAL) { "\\" + it.value } + ">"
    }
}
