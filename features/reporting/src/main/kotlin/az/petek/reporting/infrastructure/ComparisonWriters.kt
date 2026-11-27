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

package az.petek.reporting.infrastructure

import az.petek.evidence.domain.RunRecord
import az.petek.reporting.domain.ComparisonWriter
import az.petek.reporting.domain.PageComparison
import az.petek.reporting.domain.RunComparison
import az.petek.reporting.domain.SpeedChange
import az.petek.reporting.domain.StepChange
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.VisualGate
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.figcaption
import kotlinx.html.figure
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import java.nio.file.Path

/** How a comparison names its parts to the owner (Azerbaijani, like the report). */
internal object ComparisonText {
    const val TITLE = "Versiyaların müqayisəsi"
    const val WORSE = "pisləşib"
    const val NOT_WORSE = "pisləşmə yoxdur"
    const val SCENARIO_CHANGED =
        "İki run fərqli ssenari faylından gəlib (heş fərqlidir): addımlar id-ləri ilə tutuşdurulur, yalnız birində olan " +
            "addım \"yeni addım\" və ya \"çıxarılıb\" sayılır."
    const val NOT_TIMED =
        "Sürət yalnız saytın özünün təyin etdiyi yerdə tutuşdurulur: canlı çatdırılma (t1 − t0), deterministik `run` " +
            "addımları və brauzerin ölçdüyü səhifə vaxtları (yüklənmə, LCP, CLS; `site_health`-in `perf`-i). AI addımının " +
            "müddəti əsasən AI-ın düşünmə vaxtıdır, ona görə tutuşdurulmur."

    fun change(value: StepChange): String =
        when (value) {
            StepChange.NEW_FAILURE -> "yeni sınıb"
            StepChange.FIXED -> "düzəlib"
            StepChange.STILL_FAILING -> "hələ də sınıq"
            StepChange.FAILING -> "sınıq (əvvəlki run yoxlamamışdı)"
            StepChange.UNCHANGED -> "dəyişməyib"
            StepChange.NOT_COMPARABLE -> "müqayisə olunmur"
            StepChange.ADDED -> "yeni addım"
            StepChange.REMOVED -> "çıxarılıb"
        }

    fun speed(value: SpeedChange?): String =
        when (value) {
            SpeedChange.SLOWER -> "yavaşlayıb"
            SpeedChange.FASTER -> "sürətlənib"
            null -> ""
        }

    /** `run_… (versiya v1.4.2, 2026-09-30 10:00 UTC, keçdi)`. */
    fun run(record: RunRecord): String {
        val parts =
            listOfNotNull(
                record.release?.let { "versiya $it" },
                ReportFormat.instant(record.startedAt),
                ReportFormat.runResult(record.result),
            )
        return "${record.runId.value} (${parts.joinToString(", ")})"
    }

    /** `/elanlar (phone)`. */
    fun page(page: PageComparison): String = page.page + (page.device?.let { " ($it)" } ?: "")

    /** `900 ms → 1,4 san`, or a dash where a run did not time it. */
    fun times(
        before: Long?,
        after: Long?,
    ): String = "${before?.let(ReportFormat::duration) ?: ReportFormat.NONE} → ${after?.let(ReportFormat::duration) ?: ReportFormat.NONE}"

    fun shift(
        before: Double?,
        after: Double?,
    ): String = "${ReportFormat.shift(before)} → ${ReportFormat.shift(after)}"

    /** What got worse on a page: slower, jumpier, or both. */
    fun pageVerdict(page: PageComparison): String =
        listOfNotNull(speed(page.speed).ifEmpty { null }, "daha çox sürüşür".takeIf { page.shiftGrew }).joinToString(", ")

    fun thresholds(comparison: RunComparison): String =
        "Dəyişmiş sayılan vaxt: ${ReportFormat.percent(comparison.thresholds.ratio)}-dən çox və ən azı " +
            "${comparison.thresholds.atLeast.toMillis()} ms."

    /** Where the baseline's report is, seen from the current run's report directory (`<evidence>/<run>/report/`). */
    fun baselineReport(comparison: RunComparison): String = "../../${comparison.baseline.runId.value}/report/index.html"
}

/**
 * The comparison as one self-contained page beside the current run's report (`report/compare-<baseline run>.html`): the verdict, what
 * broke, what got fixed, every step and every kind of real-time event before and after. The report's own stylesheet;
 * all data goes through kotlinx.html escaping.
 */
class ComparisonHtmlWriter : ComparisonWriter {
    override fun fileName(comparison: RunComparison): String = "compare-${comparison.baseline.runId.value}.html"

    override fun write(
        comparison: RunComparison,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName(comparison), render(comparison))

    fun render(comparison: RunComparison): String =
        buildString {
            append("<!DOCTYPE html>\n")
            appendHTML().html {
                attributes["lang"] = "az"
                head {
                    meta(charset = "utf-8")
                    meta(name = "viewport", content = "width=device-width, initial-scale=1")
                    meta(name = "color-scheme", content = "light dark")
                    title("${ComparisonText.TITLE}: ${comparison.current.campaignName}")
                    style { unsafe { raw(HtmlReportWriter.CSS) } }
                }
                body {
                    div("wrap") {
                        header("page-header") {
                            h1 { +"${ComparisonText.TITLE}: ${comparison.current.campaignName}" }
                            div("meta") {
                                span { +"Əvvəlki: ${ComparisonText.run(comparison.baseline)}" }
                                +" · "
                                span { +"İndiki: ${ComparisonText.run(comparison.current)}" }
                            }
                        }
                        main {
                            summary(comparison)
                            steps(comparison)
                            deliveries(comparison)
                            pages(comparison)
                            looks(comparison)
                        }
                        footer {
                            a(href = "index.html") { +"İndiki run-ın hesabatı" }
                            +" · "
                            a(href = ComparisonText.baselineReport(comparison)) { +"Əvvəlki run-ın hesabatı" }
                        }
                    }
                }
            }
        }

    private fun FlowContent.summary(comparison: RunComparison) {
        section {
            h2 { +"Xülasə" }
            p {
                +"Nəticə: "
                span("pill ${LookText.verdictTone(comparison)}") { +LookText.verdict(comparison) }
            }
            div("tiles") {
                tile("Yeni sınan", comparison.newFailures.size, if (comparison.newFailures.isEmpty()) null else "bad")
                tile("Düzələn", comparison.fixed.size, if (comparison.fixed.isEmpty()) null else "ok")
                tile("Hələ də sınıq", comparison.steps.count { it.change == StepChange.STILL_FAILING }, null)
                val slower = comparison.slowerSteps.size + comparison.slowerDeliveries.size + comparison.worsePages.size
                tile("Yavaşlayan", slower, if (slower == 0) null else "bad")
                if (comparison.looks.isNotEmpty()) {
                    val changed = comparison.changedLooks.size
                    val tone = if (comparison.visualGate == VisualGate.FAIL) "bad" else "warn"
                    tile(LookText.CHANGED_TILE, changed, if (changed == 0) null else tone)
                    val incomparable = comparison.incomparableLooks.size
                    tile(LookText.INCOMPARABLE_TILE, incomparable, if (incomparable == 0) null else "muted")
                }
            }
            if (comparison.newFailures.isNotEmpty()) {
                p { +"Yeni sınan addımlar:" }
                ul { comparison.newFailures.forEach { li { +it.scenarioStep } } }
            }
            if (comparison.scenarioChanged) p("note") { +ComparisonText.SCENARIO_CHANGED }
            p("muted") { +"${ComparisonText.NOT_TIMED} ${ComparisonText.thresholds(comparison)}" }
        }
    }

    private fun FlowContent.tile(
        label: String,
        value: Int,
        tone: String?,
    ) {
        div("tile${tone?.let { " $it" }.orEmpty()}") {
            div("k") { +label }
            div("v") { +value.toString() }
        }
    }

    private fun FlowContent.steps(comparison: RunComparison) {
        section {
            h2 { +"Addımlar (${comparison.steps.size})" }
            div("scroll") {
                table {
                    thead {
                        tr {
                            th { +"Addım" }
                            th { +"Dəyişiklik" }
                            th(classes = "num") { +"Əvvəl" }
                            th(classes = "num") { +"İndi" }
                            th { +"Sürət" }
                        }
                    }
                    tbody {
                        comparison.steps.forEach { step ->
                            tr {
                                td { +step.scenarioStep }
                                td { span("pill ${tone(step.change)}") { +ComparisonText.change(step.change) } }
                                td("num") { +(step.beforeMs?.let(ReportFormat::duration) ?: ReportFormat.NONE) }
                                td("num") { +(step.afterMs?.let(ReportFormat::duration) ?: ReportFormat.NONE) }
                                td { +ComparisonText.speed(step.speed) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.deliveries(comparison: RunComparison) {
        if (comparison.deliveries.isEmpty()) return
        section {
            h2 { +"Canlı çatdırılma (${comparison.deliveries.size})" }
            div("scroll") {
                table {
                    thead {
                        tr {
                            th { +"Hadisə" }
                            th(classes = "num") { +"p50 əvvəl → indi" }
                            th(classes = "num") { +"p95 əvvəl → indi" }
                            th { +"Sürət" }
                        }
                    }
                    tbody {
                        comparison.deliveries.forEach { delivery ->
                            tr {
                                td { +delivery.event }
                                td("num") { +"${delivery.beforeP50Ms} ms → ${delivery.afterP50Ms} ms" }
                                td("num") { +"${delivery.beforeP95Ms} ms → ${delivery.afterP95Ms} ms" }
                                td { +ComparisonText.speed(delivery.speed) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.pages(comparison: RunComparison) {
        if (comparison.pages.isEmpty()) return
        section {
            h2 { +"Səhifələrin sürəti (${comparison.pages.size})" }
            div("scroll") {
                table {
                    thead {
                        tr {
                            th { +"Səhifə" }
                            th(classes = "num") { +"Yüklənmə əvvəl → indi" }
                            th(classes = "num") { +"Əsas məzmun (LCP) əvvəl → indi" }
                            th(classes = "num") { +"Sürüşmə (CLS) əvvəl → indi" }
                            th { +"Dəyişiklik" }
                        }
                    }
                    tbody {
                        comparison.pages.forEach { page ->
                            tr {
                                td { +ComparisonText.page(page) }
                                td("num") { +ComparisonText.times(page.beforeLoadMs, page.afterLoadMs) }
                                td("num") { +ComparisonText.times(page.beforePaintMs, page.afterPaintMs) }
                                td("num") { +ComparisonText.shift(page.beforeShift, page.afterShift) }
                                td { +ComparisonText.pageVerdict(page) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The pages' looks (docs/adr/0014): the method and gate, each changed look with its crops (before, now, difference),
     * the whole pages, what was not compared, mask suggestions and where each side's capture came from; then every look
     * in a table and the difference picture's legend.
     */
    private fun FlowContent.looks(comparison: RunComparison) {
        if (comparison.looks.isEmpty()) return
        section {
            id = "gorunus"
            h2 { +"${LookText.TITLE} (${comparison.looks.size})" }
            p("muted") { +LookText.method(comparison) }
            comparison.changedLooks.forEach { changedLook(comparison, it) }
            div("scroll") {
                table {
                    thead {
                        tr {
                            th { +"Addım" }
                            th { +"Səhifə" }
                            th { +"Ekran" }
                            th { +"Nəticə" }
                            th(classes = "num") { +"Dəyişən %" }
                            th(classes = "num") { +"Tutuşdurulmayan %" }
                            th(classes = "num") { +"Nümunə" }
                        }
                    }
                    tbody {
                        comparison.looks.forEach { look ->
                            tr {
                                td { +look.key.scenarioStep }
                                td { +look.key.page }
                                td { +LookText.device(look.key.device) }
                                td("detail") {
                                    val pill = "pill ${LookText.tone(look, comparison.visualGate)}"
                                    if (look.change == LookChange.CHANGED) {
                                        a(href = "#${anchor(look)}") { span(pill) { +LookText.change(look) } }
                                    } else {
                                        span(pill) { +LookText.change(look) }
                                    }
                                    LookText.reason(look)?.let { +" $it" }
                                }
                                td("num") { +(if (look.comparedPixels > 0) LookText.share(look.changedShare) else ReportFormat.NONE) }
                                td("num") { +(if (look.capturedPixels > 0) LookText.share(look.ignoredShare) else ReportFormat.NONE) }
                                td("num") { +LookText.samples(look) }
                            }
                        }
                    }
                }
            }
            p("legend muted") {
                LookText.LEGEND.forEach { (swatch, text) ->
                    span("item") {
                        span("sw $swatch") {}
                        +text
                    }
                }
            }
            p("muted") { +LookText.PICTURES }
        }
    }

    private fun FlowContent.changedLook(
        comparison: RunComparison,
        look: LookComparison,
    ) {
        details("look ${LookText.tone(look, comparison.visualGate)}") {
            attributes["open"] = "open"
            id = anchor(look)
            summary { +"${LookText.title(look)} — ${LookText.summary(look)}" }
            val facts = LookText.facts(look)
            if (facts.isNotEmpty()) ul { facts.forEach { li { +it } } }
            look.files?.crops?.forEach { crop ->
                p("cap") { +LookText.crop(crop.number, crop.box.width, crop.box.height, crop.box.x, crop.box.y) }
                div("looks") {
                    picture(crop.before, "Əvvəl")
                    picture(crop.after, "İndi")
                    picture(crop.diff, "Fərq")
                }
            }
            p {
                +"Bütün səhifə: "
                link(look.files?.before, "əvvəl")
                +" · "
                link(look.files?.after, "indi")
                +" · "
                link(look.files?.overlay, "fərq")
            }
            LookText.ignored(look)?.let { p("muted") { +"Tutuşdurulmayan: $it" } }
            if (look.suggestions.isNotEmpty()) {
                p { +LookText.SUGGESTIONS }
                pre("mask") { +LookText.suggestionsYaml(look) }
            }
            look.before?.let { p("muted prov") { +"Əvvəl: ${LookText.provenance(it)}" } }
            look.after?.let { p("muted prov") { +"İndi: ${LookText.provenance(it)}" } }
            p("muted") { link("${visualDirectory(comparison)}/visual.json", LookText.CALCULATION) }
        }
    }

    /** A linked lazy picture with its caption, or the caption alone when the link is not a safe relative path. */
    private fun FlowContent.picture(
        link: String,
        caption: String,
    ) {
        figure {
            ReportFormat.safeLink(link)?.let { safe ->
                a(href = safe) { img(alt = caption, src = safe) { attributes["loading"] = "lazy" } }
            }
            figcaption { +caption }
        }
    }

    private fun FlowContent.link(
        link: String?,
        text: String,
    ) {
        val safe = ReportFormat.safeLink(link)
        if (safe == null) +text else a(href = safe) { +text }
    }

    private fun anchor(look: LookComparison): String = "look-" + look.index.toString().padStart(3, '0')

    private fun visualDirectory(comparison: RunComparison): String = "visual/${comparison.baseline.runId.value}"

    private fun tone(change: StepChange): String =
        when (change) {
            StepChange.NEW_FAILURE, StepChange.STILL_FAILING, StepChange.FAILING -> "bad"
            StepChange.FIXED, StepChange.UNCHANGED -> "ok"
            StepChange.ADDED, StepChange.REMOVED -> "info"
            StepChange.NOT_COMPARABLE -> "muted"
        }
}

/** The comparison as Markdown beside the current run's report (`report/compare-<baseline run>.md`), for a CI job summary or a PR. */
class ComparisonMarkdownWriter : ComparisonWriter {
    override fun fileName(comparison: RunComparison): String = "compare-${comparison.baseline.runId.value}.md"

    override fun write(
        comparison: RunComparison,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName(comparison), render(comparison))

    fun render(comparison: RunComparison): String =
        buildString {
            appendLine("# ${ComparisonText.TITLE}: ${cell(comparison.current.campaignName)}")
            appendLine()
            appendLine("- Əvvəlki: ${cell(ComparisonText.run(comparison.baseline))}")
            appendLine("- İndiki: ${cell(ComparisonText.run(comparison.current))}")
            appendLine("- Nəticə: **${LookText.verdict(comparison)}**")
            appendLine(
                "- Yeni sınan: ${comparison.newFailures.size} · Düzələn: ${comparison.fixed.size} · Yavaşlayan: " +
                    "${comparison.slowerSteps.size + comparison.slowerDeliveries.size + comparison.worsePages.size}" +
                    if (comparison.looks.isEmpty()) "" else " · ${LookText.CHANGED_TILE}: ${comparison.changedLooks.size}",
            )
            if (comparison.scenarioChanged) appendLine("- ${ComparisonText.SCENARIO_CHANGED}")
            appendLine()
            appendLine("## Addımlar")
            appendLine()
            appendLine("| Addım | Dəyişiklik | Əvvəl | İndi | Sürət |")
            appendLine("|---|---|---:|---:|---|")
            comparison.steps.forEach { step ->
                appendLine(
                    "| ${cell(step.scenarioStep)} | ${ComparisonText.change(step.change)} | " +
                        "${step.beforeMs?.let(ReportFormat::duration) ?: ReportFormat.NONE} | " +
                        "${step.afterMs?.let(ReportFormat::duration) ?: ReportFormat.NONE} | ${ComparisonText.speed(step.speed)} |",
                )
            }
            if (comparison.deliveries.isNotEmpty()) {
                appendLine()
                appendLine("## Canlı çatdırılma")
                appendLine()
                appendLine("| Hadisə | p50 əvvəl → indi | p95 əvvəl → indi | Sürət |")
                appendLine("|---|---:|---:|---|")
                comparison.deliveries.forEach { delivery ->
                    appendLine(
                        "| ${cell(delivery.event)} | ${delivery.beforeP50Ms} ms → ${delivery.afterP50Ms} ms | " +
                            "${delivery.beforeP95Ms} ms → ${delivery.afterP95Ms} ms | ${ComparisonText.speed(delivery.speed)} |",
                    )
                }
            }
            if (comparison.pages.isNotEmpty()) {
                appendLine()
                appendLine("## Səhifələrin sürəti")
                appendLine()
                appendLine("| Səhifə | Yüklənmə əvvəl → indi | LCP əvvəl → indi | CLS əvvəl → indi | Dəyişiklik |")
                appendLine("|---|---:|---:|---:|---|")
                comparison.pages.forEach { page ->
                    appendLine(
                        "| ${cell(ComparisonText.page(page))} | ${ComparisonText.times(page.beforeLoadMs, page.afterLoadMs)} | " +
                            "${ComparisonText.times(page.beforePaintMs, page.afterPaintMs)} | " +
                            "${ComparisonText.shift(page.beforeShift, page.afterShift)} | ${ComparisonText.pageVerdict(page)} |",
                    )
                }
            }
            looks(comparison)
            appendLine()
            appendLine("${ComparisonText.NOT_TIMED} ${ComparisonText.thresholds(comparison)}")
        }

    /** Every look in a table with links to its difference picture, then each changed look's picture. */
    private fun StringBuilder.looks(comparison: RunComparison) {
        if (comparison.looks.isEmpty()) return
        appendLine()
        appendLine("## ${LookText.TITLE}")
        appendLine()
        appendLine(LookText.method(comparison))
        appendLine()
        appendLine("| Addım | Səhifə | Ekran | Nəticə | Fərq |")
        appendLine("|---|---|---|---|---|")
        comparison.looks.forEach { look ->
            val result = listOfNotNull(LookText.change(look), LookText.reason(look)).joinToString(": ")
            val difference =
                if (look.change == LookChange.CHANGED) {
                    val overlay = ReportFormat.safeLink(look.files?.overlay)
                    LookText.summary(look).substringAfter(": ") + (overlay?.let { " · [fərq](${destination(it)})" } ?: "")
                } else {
                    ReportFormat.NONE
                }
            appendLine(
                "| ${cell(look.key.scenarioStep)} | ${cell(look.key.page)} | ${cell(LookText.device(look.key.device))} | " +
                    "${cell(result)} | ${cell(difference)} |",
            )
        }
        comparison.changedLooks.forEach { look ->
            val overlay = ReportFormat.safeLink(look.files?.overlay) ?: return@forEach
            appendLine()
            appendLine("**${cell(LookText.title(look))}** — ${cell(LookText.summary(look))}")
            appendLine()
            appendLine("![fərq](${destination(overlay)})")
        }
    }

    /** A link destination inside Markdown: spaces and parentheses escaped so the link cannot end early. */
    private fun destination(link: String): String = link.replace(" ", "%20").replace("(", "%28").replace(")", "%29")

    /** Text inside a Markdown table or line: pipes escaped, line breaks flattened. */
    private fun cell(text: String): String = text.replace("|", "\\|").replace('\n', ' ')
}
