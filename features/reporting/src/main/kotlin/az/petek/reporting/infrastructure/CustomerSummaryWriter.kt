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
import az.petek.evidence.domain.RunResult
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportWriter
import az.petek.reporting.domain.Shelf
import java.nio.file.Path

/**
 * The customer layer of the report (`report/summary.html`, Faza 20): one page in short sentences for the site's owner
 * (not a tester), findings on three shelves ([Shelf]), and a link to the detail layer (`index.html`) with every step and
 * its proof. Written in Azerbaijani, or in English when [english] (the owner's working language).
 */
class CustomerSummaryWriter(
    private val english: Boolean = false,
) : ReportWriter {
    override val fileName: String = "summary.html"

    override fun write(
        model: ReportModel,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName, render(model))

    fun render(model: ReportModel): String {
        val t = if (english) EN else AZ
        val run = model.run
        val s = model.summary
        val byShelf = model.findings.groupBy(Shelf::of)
        return buildString {
            append("<!DOCTYPE html>\n<html lang=\"").append(if (english) "en" else "az").append("\"><head><meta charset=\"utf-8\">")
            append(
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"color-scheme\" content=\"light dark\">",
            )
            append(
                "<title>",
            ).append(esc(t.title(run.campaignName))).append("</title><style>").append(CSS).append("</style></head><body><main>")
            append("<h1>").append(esc(t.title(run.campaignName))).append("</h1>")
            append("<p class=\"lead\">").append(esc(t.lead(run.target, s.agents, ReportFormat.duration(s.durationMs)))).append("</p>")
            append("<p class=\"verdict ")
                .append(
                    if (model.findings.isEmpty() &&
                        run.result == RunResult.PASSED
                    ) {
                        "ok"
                    } else {
                        "bad"
                    },
                ).append("\">")
            append(esc(t.verdict(model.findings.size, s.stepsPassed, s.stepsPassed + s.stepsFailed))).append("</p>")
            Shelf.entries.forEach { shelf ->
                val findings = byShelf[shelf].orEmpty()
                if (findings.isEmpty()) return@forEach
                append("<section><h2>")
                    .append(esc(t.shelf(shelf)))
                    .append(" (")
                    .append(findings.size)
                    .append(")</h2><ul>")
                findings.forEach { append("<li>").append(esc(t.sentence(it))).append("</li>") }
                append("</ul></section>")
            }
            append("<p class=\"more\"><a href=\"index.html\">").append(esc(t.details)).append("</a></p>")
            append("</main></body></html>\n")
        }
    }

    /** The sentences of one language. */
    private interface Texts {
        fun title(campaign: String): String

        fun lead(
            target: String,
            testers: Int,
            duration: String,
        ): String

        fun verdict(
            findings: Int,
            passed: Int,
            steps: Int,
        ): String

        fun shelf(shelf: Shelf): String

        fun sentence(finding: FindingRecord): String

        val details: String
    }

    private object AZ : Texts {
        override fun title(campaign: String) = "Qısa nəticə: $campaign"

        override fun lead(
            target: String,
            testers: Int,
            duration: String,
        ) = "Pətək $target saytını $testers testerlə $duration ərzində yoxladı."

        override fun verdict(
            findings: Int,
            passed: Int,
            steps: Int,
        ) = if (findings ==
            0
        ) {
            "Problem tapılmadı: $steps addımdan $passed-i keçdi."
        } else {
            "$findings problem tapıldı; $steps addımdan $passed-i keçdi."
        }

        override fun shelf(shelf: Shelf) =
            when (shelf) {
                Shelf.SITE_BUG -> "Saytda düzəldilməli"
                Shelf.TOOL_GAP -> "Pətək bacarmadı (saytın xətası deyil)"
                Shelf.INVESTIGATE -> "Bir insan baxmalıdır"
            }

        override fun sentence(finding: FindingRecord): String {
            val step = "„${finding.scenarioStep}“"
            return when (finding.findingClass) {
                FindingClass.BACKEND -> "$step addımında sayt dəyişikliyi düzgün saxlamadı və ya gözlənilən cavabı vermədi."
                FindingClass.DELIVERY_UI -> "$step addımında dəyişiklik saxlanıldı, amma istifadəçilər onu ekranda görmədi."
                FindingClass.INVESTIGATE -> "$step addımında mənbələr uyğun gəlmir; səbəbi aydınlaşdırılmalıdır."
                FindingClass.FLAKY -> "$step addımı təkrar run-larda gah keçir, gah keçmir."
                FindingClass.AGENT_FAILURE -> "$step addımında tester işini bitirə bilmədi."
            }
        }

        override val details = "Bütün addımlar, sübutlar və screenshot-lar: ətraflı hesabat"
    }

    private object EN : Texts {
        override fun title(campaign: String) = "Summary: $campaign"

        override fun lead(
            target: String,
            testers: Int,
            duration: String,
        ) = "Pətək checked $target with $testers testers in $duration."

        override fun verdict(
            findings: Int,
            passed: Int,
            steps: Int,
        ) = if (findings ==
            0
        ) {
            "No problems found: $passed of $steps steps passed."
        } else {
            "$findings problems found; $passed of $steps steps passed."
        }

        override fun shelf(shelf: Shelf) =
            when (shelf) {
                Shelf.SITE_BUG -> "To fix on the site"
                Shelf.TOOL_GAP -> "Pətək could not do it (not the site's fault)"
                Shelf.INVESTIGATE -> "A person should look"
            }

        override fun sentence(finding: FindingRecord): String {
            val step = "\"${finding.scenarioStep}\""
            return when (finding.findingClass) {
                FindingClass.BACKEND -> "In step $step the site did not store the change correctly or answered wrongly."
                FindingClass.DELIVERY_UI -> "In step $step the change was stored, but users did not see it on screen."
                FindingClass.INVESTIGATE -> "In step $step the sources disagree; the cause needs a closer look."
                FindingClass.FLAKY -> "Step $step passes in some runs and fails in others."
                FindingClass.AGENT_FAILURE -> "In step $step a tester could not finish its task."
            }
        }

        override val details = "Every step, its proof and screenshots: the detailed report"
    }

    private fun esc(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private companion object {
        const val CSS =
            ":root{color-scheme:light dark;--fg:#1f2937;--bg:#fff;--ok:#15803d;--bad:#b91c1c;--muted:#6b7280}" +
                "@media (prefers-color-scheme: dark){:root{--fg:#e5e7eb;--bg:#111827;--ok:#4ade80;--bad:#f87171;--muted:#9ca3af}}" +
                "body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 system-ui,sans-serif}" +
                "main{max-width:720px;margin:0 auto;padding:32px 20px}h1{font-size:1.6rem;margin:0 0 8px}" +
                ".lead{color:var(--muted)}.verdict{font-weight:700;font-size:1.15rem}.verdict.ok{color:var(--ok)}" +
                ".verdict.bad{color:var(--bad)}h2{font-size:1.1rem;margin-top:28px}li{margin:6px 0}.more{margin-top:32px}"
    }
}
