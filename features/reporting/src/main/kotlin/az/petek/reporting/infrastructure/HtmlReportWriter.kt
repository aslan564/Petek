package az.petek.reporting.infrastructure

import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunResult
import az.petek.reporting.domain.LatencyStats
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportWriter
import az.petek.reporting.infrastructure.ReportFormat.NONE
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.TBODY
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.body
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
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

/**
 * Writes `index.html`: one self-contained page (inline CSS, light and dark via `prefers-color-scheme`, no scripts,
 * no network) that opens straight from the evidence directory. Screenshots are linked relatively, so the page and
 * the run's artifacts travel together. All data goes through kotlinx.html text and attribute escaping; the only
 * raw markup is the constant stylesheet. Public because the composition root assembles the writer list.
 */
class HtmlReportWriter : ReportWriter {
    override val fileName: String = "index.html"

    override fun write(
        model: ReportModel,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName, render(model))

    /** The HTML document for [model]. */
    fun render(model: ReportModel): String =
        buildString {
            append("<!DOCTYPE html>\n")
            appendHTML().html {
                attributes["lang"] = "az"
                head { head(model) }
                body {
                    div("wrap") {
                        pageHeader(model)
                        main {
                            summary(model)
                            findings(model)
                            steps(model)
                            latency(model)
                            stability(model)
                            failedAgents(model)
                            usage(model)
                        }
                        footer { +"Pətək · run ${model.run.runId.value} · kampaniya heşi ${model.run.campaignHash}" }
                    }
                }
            }
        }

    private fun HEAD.head(model: ReportModel) {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        meta(name = "color-scheme", content = "light dark")
        title(ReportFormat.title(model))
        style { unsafe { raw(CSS) } }
    }

    private fun FlowContent.pageHeader(model: ReportModel) {
        val run = model.run
        header("page-header") {
            h1 { +ReportFormat.title(model) }
            ul("meta") {
                li { +"Run: ${run.runId.value}" }
                li { +"Hədəf: ${run.target}" }
                li { +"Başladı: ${ReportFormat.instant(run.startedAt)}" }
                li { +"Bitdi: ${ReportFormat.instant(run.endedAt)}" }
                li { +"Seed: ${run.seed}" }
                run.repeatGroup?.let { group ->
                    li { +("Təkrar qrupu: $group" + (run.repeatIndex?.let { " (#$it)" } ?: "")) }
                }
                li {
                    +"Nəticə: "
                    pill(ReportFormat.runResult(run.result), runTone(model))
                }
            }
        }
    }

    private fun FlowContent.summary(model: ReportModel) {
        val s = model.summary
        section {
            h2 { +"Xülasə" }
            div("tiles") {
                tile("Keçən addımlar", s.stepsPassed.toString(), "ok")
                tile("Keçməyən addımlar", s.stepsFailed.toString(), if (s.stepsFailed > 0) "bad" else null)
                tile(
                    "Assertlər",
                    "${s.assertionsPassed} / ${s.assertionsFailed} / ${s.assertionsSkipped}",
                    null,
                    "keçdi / keçmədi / ötürüldü",
                )
                tile("Tapıntılar", model.findings.size.toString(), if (model.findings.isNotEmpty()) "bad" else "ok")
                tile("Agentlər", s.agents.toString())
                tile("Müddət", ReportFormat.duration(s.durationMs))
                tile("Tokenlər", ReportFormat.count(s.inputTokens + s.outputTokens), null, tokenHint(model))
                tile("Xərc", ReportFormat.cost(s.costUsd))
                tile("Real-time", s.realtimeTransports.joinToString(", ").ifEmpty { NONE }, null, "aşkar edilən nəqliyyat")
            }
        }
    }

    private fun FlowContent.findings(model: ReportModel) {
        section {
            h2 { +"Tapıntılar (${model.findings.size})" }
            if (model.findings.isEmpty()) return@section empty("Tapıntı yoxdur: bütün mənbələr uyğun gəlir.")
            model.findings.forEachIndexed { i, finding -> finding(model, i + 1, finding) }
        }
    }

    private fun FlowContent.finding(
        model: ReportModel,
        number: Int,
        finding: FindingRecord,
    ) {
        article("finding ${findingTone(finding.findingClass)}") {
            h3 {
                pill(ReportFormat.findingClass(finding.findingClass), findingTone(finding.findingClass))
                +" $number. ${finding.scenarioStep} · "
                val agent = finding.agentId?.value
                +ReportFormat.agent(agent, ReportFormat.agentName(model, agent))
            }
            div("abc") {
                source("A · göndərən", finding.a)
                source("B · alan", finding.b)
                source("C · oracle", finding.c)
            }
            p("note") { +finding.note }
            val links = finding.artifactIds.mapNotNull { id -> ReportFormat.safeLink(model.artifactLinks[id.value]) }
            if (links.isNotEmpty()) evidence(links)
        }
    }

    private fun FlowContent.source(
        label: String,
        value: String?,
    ) {
        div("source") {
            div("label") { +label }
            div(if (value == null) "value muted" else "value") { +(value ?: NONE) }
        }
    }

    private fun FlowContent.evidence(links: List<String>) {
        div("thumbs") {
            links.forEach { link ->
                a(href = link) {
                    if (ReportFormat.isImage(link)) {
                        img(src = link, alt = "screenshot ${ReportFormat.fileLabel(link)}") { attributes["loading"] = "lazy" }
                    } else {
                        span("file") { +ReportFormat.fileLabel(link) }
                    }
                }
            }
        }
    }

    private fun FlowContent.steps(model: ReportModel) {
        section {
            h2 { +"Addımlar (${model.steps.size})" }
            if (model.steps.isEmpty()) return@section empty("Addım qeydə alınmayıb.")
            dataTable(listOf("Addım", "Agent", "Növ", "Nəticə", "Müddət", "Detal", "Screenshot"), numeric = setOf(4)) {
                model.steps.forEach { row ->
                    tr {
                        td { +row.scenarioStep }
                        td { +ReportFormat.agent(row.agentId, row.agentName) }
                        td { +row.kind.lowercase() }
                        td { pill(ReportFormat.stepStatus(row.status), ReportFormat.stepTone(row.status)) }
                        td("num") { +ReportFormat.duration(row.durationMs) }
                        td("detail") { +(row.detail ?: NONE) }
                        td {
                            val link = ReportFormat.safeLink(row.screenshot?.let { model.artifactLinks[it] })
                            if (link == null) {
                                +NONE
                            } else {
                                a(href = link) { img(src = link, alt = "screenshot", classes = "thumb") { attributes["loading"] = "lazy" } }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.latency(model: ReportModel) {
        section {
            h2 { +"Real-time gecikmə" }
            if (model.latency.isEmpty()) return@section empty("Real-time hadisəsi olmayıb.")
            dataTable(listOf("Hadisə", "Alanlar", "Çatdı", "Orta", "p95", "Maks", "Çatmayanlar"), numeric = setOf(1, 2, 3, 4, 5)) {
                model.latency.forEach { stats -> latencyRow(stats) }
            }
            model.latency.filter { it.perReceiverMs.isNotEmpty() }.forEach { stats ->
                details {
                    summary { +"${stats.event}: alan başına gecikmə" }
                    dataTable(listOf("Alan", "Gecikmə"), numeric = setOf(1)) {
                        stats.perReceiverMs.forEach { (agent, ms) ->
                            tr {
                                td { +agent }
                                td(if (ms == null) "num bad-text" else "num") { +(ms?.let { "$it ms" } ?: "çatmadı") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun TBODY.latencyRow(stats: LatencyStats) {
        tr {
            td { +stats.event }
            td("num") { +stats.receivers.toString() }
            td("num") { +stats.received.toString() }
            td("num") { +ReportFormat.latency(stats.avgMs) }
            td("num") { +ReportFormat.latency(stats.p95Ms) }
            td("num") { +ReportFormat.latency(stats.maxMs) }
            td { +stats.missing.joinToString(", ").ifEmpty { NONE } }
        }
    }

    private fun FlowContent.stability(model: ReportModel) {
        val rows = model.stability ?: return
        section {
            h2 { +"Stabillik" }
            if (rows.isEmpty()) return@section empty("Təkrar qrupunda addım yoxdur.")
            dataTable(listOf("Addım", "Run-lar", "Keçdi", "Keçmə faizi", "Vəziyyət"), numeric = setOf(1, 2, 3)) {
                rows.forEach { row ->
                    tr {
                        td { +row.scenarioStep }
                        td("num") { +row.runs.toString() }
                        td("num") { +row.passed.toString() }
                        td("num") { +ReportFormat.percent(row.passRate) }
                        td {
                            val tone =
                                if (row.flaky) {
                                    "warn"
                                } else if (row.runs > 0 && row.passed == row.runs) {
                                    "ok"
                                } else {
                                    "bad"
                                }
                            pill(ReportFormat.stability(row), tone)
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.failedAgents(model: ReportModel) {
        section {
            h2 { +"Uğursuz agentlər (${model.failedAgents.size})" }
            if (model.failedAgents.isEmpty()) return@section empty("Bütün agentlər addımlarını tamamladı.")
            dataTable(listOf("Agent", "Ad", "Addım", "Səbəb")) {
                model.failedAgents.forEach { row ->
                    tr {
                        td { +row.agentId }
                        td { +row.name }
                        td { +row.scenarioStep }
                        td { +row.reason }
                    }
                }
            }
        }
    }

    private fun FlowContent.usage(model: ReportModel) {
        val s = model.summary
        section {
            h2 { +"İstifadə: token və xərc" }
            dataTable(listOf("Agent", "Giriş", "Çıxış", "Keşdən oxunan", "Çağırışlar", "Xərc"), numeric = setOf(1, 2, 3, 4, 5)) {
                model.usage.sortedBy { it.agentId.index }.forEach { u ->
                    tr {
                        td { +u.agentId.value }
                        numbers(
                            ReportFormat.count(u.inputTokens),
                            ReportFormat.count(u.outputTokens),
                            ReportFormat.count(u.cacheReadTokens),
                            u.calls.toString(),
                            ReportFormat.cost(u.costUsd),
                        )
                    }
                }
                tr("total") {
                    td { +"Cəmi" }
                    numbers(
                        ReportFormat.count(s.inputTokens),
                        ReportFormat.count(s.outputTokens),
                        ReportFormat.count(model.usage.sumOf { it.cacheReadTokens }),
                        model.usage.sumOf { it.calls }.toString(),
                        ReportFormat.cost(s.costUsd),
                    )
                }
            }
        }
    }

    private fun TR.numbers(vararg values: String) {
        values.forEach { value -> td("num") { +value } }
    }

    private fun FlowContent.dataTable(
        headers: List<String>,
        numeric: Set<Int> = emptySet(),
        rows: TBODY.() -> Unit,
    ) {
        div("scroll") {
            table {
                thead {
                    tr { headers.forEachIndexed { i, h -> th(classes = if (i in numeric) "num" else null) { +h } } }
                }
                tbody { rows() }
            }
        }
    }

    private fun FlowContent.tile(
        label: String,
        value: String,
        tone: String? = null,
        hint: String? = null,
    ) {
        div(listOfNotNull("tile", tone).joinToString(" ")) {
            div("k") { +label }
            div("v") { +value }
            hint?.let { div("h") { +it } }
        }
    }

    private fun FlowContent.pill(
        text: String,
        tone: String,
    ) {
        span("pill $tone") { +text }
    }

    private fun FlowContent.empty(text: String) {
        p("empty") { +text }
    }

    private fun tokenHint(model: ReportModel): String =
        "giriş ${ReportFormat.count(model.summary.inputTokens)} · çıxış ${ReportFormat.count(model.summary.outputTokens)}"

    private fun runTone(model: ReportModel): String =
        when (model.run.result) {
            RunResult.PASSED -> "ok"
            RunResult.RUNNING -> "muted"
            RunResult.FAILED, RunResult.ABORTED -> "bad"
        }

    private fun findingTone(value: FindingClass): String =
        when (value) {
            FindingClass.BACKEND, FindingClass.DELIVERY_UI -> "bad"
            FindingClass.INVESTIGATE, FindingClass.FLAKY -> "warn"
            FindingClass.AGENT_FAILURE -> "info"
        }

    private companion object {
        /** Constant stylesheet: the only raw markup in the page. Never interpolate data into it. */
        val CSS =
            """
            :root {
              color-scheme: light dark;
              --bg: #f6f6f3; --surface: #ffffff; --sunken: #f0f0ec; --text: #1c1c1e; --muted: #66666d;
              --border: #e2e2dd; --ok: #1a7f37; --ok-bg: #e5f3e9; --bad: #c0362c; --bad-bg: #fbe9e7;
              --warn: #8a5a00; --warn-bg: #fdf1d6; --info: #0b5cad; --info-bg: #e6effa;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #141416; --surface: #1c1c1f; --sunken: #232327; --text: #ececef; --muted: #a2a2aa;
                --border: #303036; --ok: #5cc27d; --ok-bg: #173022; --bad: #ff7b6e; --bad-bg: #3b1c19;
                --warn: #e6b450; --warn-bg: #352a10; --info: #72b4ff; --info-bg: #142a45;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--text);
              font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
            .wrap { max-width: 1200px; margin: 0 auto; padding: 24px 16px 48px; }
            h1 { font-size: 1.6rem; margin: 0 0 6px; }
            h2 { font-size: 1.2rem; margin: 36px 0 12px; }
            h3 { font-size: 1rem; margin: 0 0 8px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
            a { color: var(--info); }
            .meta { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 4px 18px;
              color: var(--muted); font-size: .9rem; overflow-wrap: anywhere; }
            .tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; }
            .tile { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; }
            .tile .k { color: var(--muted); font-size: .85rem; }
            .tile .v { font-size: 1.45rem; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
            .tile .h { color: var(--muted); font-size: .78rem; }
            .tile.ok .v { color: var(--ok); }
            .tile.bad .v { color: var(--bad); }
            .scroll { overflow-x: auto; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; }
            table { border-collapse: collapse; width: 100%; font-size: .9rem; }
            th, td { padding: 8px 10px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--border); }
            th { color: var(--muted); font-weight: 600; white-space: nowrap; }
            tbody tr:last-child td { border-bottom: 0; }
            .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
            td.detail { max-width: 420px; overflow-wrap: anywhere; }
            tr.total td { font-weight: 650; border-top: 2px solid var(--border); }
            .pill { display: inline-block; padding: 1px 9px; border-radius: 999px; font-size: .8rem; font-weight: 600;
              white-space: nowrap; }
            .pill.ok { color: var(--ok); background: var(--ok-bg); }
            .pill.bad { color: var(--bad); background: var(--bad-bg); }
            .pill.warn { color: var(--warn); background: var(--warn-bg); }
            .pill.info { color: var(--info); background: var(--info-bg); }
            .pill.muted { color: var(--muted); background: var(--sunken); }
            .bad-text { color: var(--bad); }
            .finding { background: var(--surface); border: 1px solid var(--border); border-left: 4px solid var(--bad);
              border-radius: 10px; padding: 14px 16px; margin-bottom: 12px; }
            .finding.warn { border-left-color: var(--warn); }
            .finding.info { border-left-color: var(--info); }
            .abc { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 10px 0; }
            .source { background: var(--sunken); border-radius: 8px; padding: 8px 10px; min-width: 0; }
            .source .label { color: var(--muted); font-size: .78rem; font-weight: 600; text-transform: uppercase; }
            .source .value { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .85rem;
              white-space: pre-wrap; overflow-wrap: anywhere; }
            .note { margin: 6px 0; }
            .thumbs { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
            .thumbs img { width: 180px; height: auto; display: block; border: 1px solid var(--border); border-radius: 6px; }
            .thumbs .file { display: inline-block; padding: 4px 8px; border: 1px solid var(--border); border-radius: 6px;
              font-size: .85rem; }
            img.thumb { width: 96px; height: auto; display: block; border: 1px solid var(--border); border-radius: 4px; }
            .muted, .empty { color: var(--muted); }
            .empty { font-style: italic; }
            details { margin-top: 10px; }
            details .scroll { margin-top: 8px; }
            summary { cursor: pointer; color: var(--muted); }
            footer { margin-top: 40px; color: var(--muted); font-size: .8rem; overflow-wrap: anywhere; }
            @media (max-width: 720px) { .abc { grid-template-columns: 1fr; } }
            """.trimIndent()
    }
}
