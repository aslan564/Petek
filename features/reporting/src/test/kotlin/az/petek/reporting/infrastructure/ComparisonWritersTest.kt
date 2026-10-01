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

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.LookBox
import az.petek.reporting.ReportTestData
import az.petek.reporting.domain.RunComparison
import az.petek.reporting.domain.SpeedThresholds
import az.petek.reporting.domain.visual.Band
import az.petek.reporting.domain.visual.BandKind
import az.petek.reporting.domain.visual.ChangedRegion
import az.petek.reporting.domain.visual.IgnoredBy
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.LookCrop
import az.petek.reporting.domain.visual.LookFact
import az.petek.reporting.domain.visual.LookFactKind
import az.petek.reporting.domain.visual.LookFiles
import az.petek.reporting.domain.visual.LookKey
import az.petek.reporting.domain.visual.LookReason
import az.petek.reporting.domain.visual.LookSide
import az.petek.reporting.domain.visual.VisualGate
import az.petek.reporting.domain.visual.VisualThresholds
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class ComparisonWritersTest {
    private val base = RunId("run_base")
    private val now = RunId("run_now")
    private val visual = "visual/run_base/001-public-look-qiymetler-phone"

    private fun side(
        runId: RunId,
        file: String,
        renderer: String = "chromium 141.0; Linux x86_64; headless",
        viewport: Pair<Int, Int> = 375 to 812,
    ) = LookSide(
        runId,
        AgentId("a01"),
        StepId("stp_look_${runId.value}"),
        ArtifactId("art_${runId.value}"),
        "${runId.value}/a01/$file",
        "3f2a9c1b0d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5061728394a5b6c7d8e",
        true,
        renderer,
        viewport.first,
        viewport.second,
        2_310,
        "/qiymetler",
        200,
        true,
        2,
    )

    private val changed =
        LookComparison(
            index = 1,
            key = LookKey("public-look", "/qiymetler", "phone"),
            change = LookChange.CHANGED,
            facts =
                listOf(
                    LookFact(LookFactKind.HEIGHT, "2310", "2430"),
                    LookFact(LookFactKind.FONTS, "", "Demo Sans 700 normal"),
                    LookFact(LookFactKind.TESTERS, "8", "10"),
                ),
            before = side(base, "0003-visual.png"),
            after = side(now, "0004-visual.png"),
            regions =
                listOf(
                    ChangedRegion(LookBox(0, 388, 375, 120), 9_000, 40),
                    ChangedRegion(LookBox(20, 900, 100, 40), 5_000, 10),
                    ChangedRegion(LookBox(10, 20, 120, 18), 800, 4, runContent = true),
                ),
            bands = listOf(Band(BandKind.INSERTED, 388, 120, 388, counted = true)),
            comparedPixels = 1_000_000,
            changedPixels = 14_000,
            differingPixels = 15_000,
            capturedPixels = 1_000_000,
            ignored = mapOf(IgnoredBy.RUN_TEXT to 12_000L, IgnoredBy.TIME_TEXT to 3_000L, IgnoredBy.MOVING to 40_000L),
            pairs = 4,
            suggestions = listOf("'[data-testid=\"price\"]'"),
            files =
                LookFiles(
                    "$visual.png",
                    "../../run_base/a01/0003-visual.png",
                    "../a01/0004-visual.png",
                    listOf(LookCrop(1, LookBox(0, 388, 375, 120), "$visual-r1-before.png", "$visual-r1-after.png", "$visual-r1-diff.png")),
                ),
        )

    private val otherBrowser =
        LookComparison(
            index = 2,
            key = LookKey("public-look", "/giris", "desktop"),
            change = LookChange.NOT_COMPARABLE,
            reason = LookReason.OTHER_BROWSER,
            before = side(base, "0005-visual.png", renderer = "chromium 140.0; Linux x86_64; headless"),
            after = side(now, "0006-visual.png"),
        )

    private val moving =
        LookComparison(
            index = 3,
            key = LookKey("public-look", "/", null),
            change = LookChange.NOT_COMPARABLE,
            reason = LookReason.KEPT_MOVING,
            reasonShare = 0.42,
        )

    private val unchanged = LookComparison(4, LookKey("public-look", "/haqqimizda", "tablet"), LookChange.UNCHANGED)

    private fun comparison(
        gate: VisualGate = VisualGate.REPORT,
        vararg looks: LookComparison = arrayOf(changed, otherBrowser, moving, unchanged),
    ) = RunComparison(
        baseline = ReportTestData.run(base),
        current = ReportTestData.run(now),
        scenarioChanged = false,
        steps = emptyList(),
        deliveries = emptyList(),
        thresholds = SpeedThresholds(),
        looks = looks.toList(),
        visualGate = gate,
        visualThresholds = VisualThresholds(),
    )

    /** The page with the pretty printer's line breaks between tags taken out. */
    private fun html(comparison: RunComparison) = ComparisonHtmlWriter().render(comparison).replace(Regex(">\\s+<"), "><")

    @Test
    fun `the comparison page shows before, now and difference crops for each changed look with links to the whole pages`() {
        val html = html(comparison())

        html shouldContain "<section id=\"gorunus\">"
        html shouldContain "Görünüş (4)"
        html shouldContain "id=\"look-001\""
        html shouldContain "/qiymetler · telefon · public-look — dəyişib: 2 sahə (ekranın 1,4%-i), y≈388-də 120 px əlavə olunub"
        html shouldContain "<img alt=\"Əvvəl\" src=\"$visual-r1-before.png\" loading=\"lazy\">"
        html shouldContain "<img alt=\"İndi\" src=\"$visual-r1-after.png\" loading=\"lazy\">"
        html shouldContain "<img alt=\"Fərq\" src=\"$visual-r1-diff.png\" loading=\"lazy\">"
        html shouldContain "Sahə 1: 375×120 @ 0,388"
        html shouldContain "href=\"../../run_base/a01/0003-visual.png\">əvvəl</a>"
        html shouldContain "href=\"../a01/0004-visual.png\">indi</a>"
        html shouldContain "href=\"$visual.png\">fərq</a>"
        html shouldContain "hündürlük 2310 → 2430 px"
        html shouldContain "şriftlər: +Demo Sans 700 normal"
        html shouldContain "bu addımda tester sayı fərqlidir (8 → 10)"
        html shouldContain "Sahə 120×18 @ 10,20: run-ın öz məzmunu (sayılmır)"
        html shouldContain "Tutuşdurulmayan: run mətni 1,2%, tarix/saat 0,3%, öz-özünə dəyişən 4,0%"
        html shouldContain "Əvvəl: a01 · stp_look_run_base · 0003-visual.png · sha256 3f2a9c1b0d4e yoxlanıb"
        html shouldContain "href=\"visual/run_base/visual.json\">hesablama</a>"
        html shouldContain "qırmızı — sayılan fərq"
    }

    @Test
    fun `a look that is not comparable says why in the owner's words`() {
        val html = html(comparison())

        html shouldContain
            "brauzer və ya sistem fərqlidir (chromium 140.0 → chromium 141.0); bu maşında yeni baseline çəkin"
        html shouldContain
            "səhifə öz-özünə dəyişməkdə davam etdi (sahənin 42%-i); dəyişən hissəni target_profile.visual.mask ilə maskalayın"
        html shouldContain "<span class=\"pill muted\">tutuşdurulmur</span>"
        html shouldContain "<span class=\"pill ok\">dəyişməyib</span>"
    }

    @Test
    fun `the summary says no regression but changed looks under the report gate`() {
        val report = html(comparison())
        val fail = html(comparison(VisualGate.FAIL))
        val none = html(comparison(VisualGate.REPORT, unchanged))

        report shouldContain "<span class=\"pill warn\">pisləşmə yoxdur · görünüş dəyişib (1 səhifə)</span>"
        report shouldContain "<div class=\"tile warn\"><div class=\"k\">Görünüşü dəyişən</div><div class=\"v\">1</div>"
        report shouldContain "<div class=\"tile muted\"><div class=\"k\">Görünüş tutuşdurulmadı</div><div class=\"v\">2</div>"
        report shouldContain "Qapı: report"
        fail shouldContain "<span class=\"pill bad\">pisləşib</span>"
        fail shouldContain "<div class=\"tile bad\"><div class=\"k\">Görünüşü dəyişən</div>"
        none shouldContain "<span class=\"pill ok\">pisləşmə yoxdur</span>"
    }

    @Test
    fun `page names, selectors and suggestions are escaped`() {
        val hostile =
            changed.copy(
                key = LookKey("public-look", "/<script>alert(1)</script>", "phone"),
                suggestions = listOf("'[data-x=\"<b>\"]'"),
                files = changed.files?.copy(overlay = "javascript:alert(1)"),
            )

        val html = html(comparison(VisualGate.REPORT, hostile))

        html shouldNotContain "<script>alert(1)</script>"
        html shouldContain "/&lt;script&gt;alert(1)&lt;/script&gt;"
        html shouldContain "'[data-x=&quot;&lt;b&gt;&quot;]'"
        html shouldNotContain "javascript:"
    }

    @Test
    fun `the Markdown comparison lists the looks with relative links to the difference pictures`() {
        val md = ComparisonMarkdownWriter().render(comparison())

        md shouldContain "- Nəticə: **pisləşmə yoxdur · görünüş dəyişib (1 səhifə)**"
        md shouldContain "Görünüşü dəyişən: 1"
        md shouldContain "## Görünüş"
        md shouldContain "| Addım | Səhifə | Ekran | Nəticə | Fərq |"
        md shouldContain
            "| public-look | /qiymetler | telefon | dəyişib | 2 sahə (ekranın 1,4%-i), y≈388-də 120 px əlavə olunub · [fərq]($visual.png) |"
        md shouldContain "| public-look | /giris | kompüter | tutuşdurulmur: brauzer və ya sistem fərqlidir"
        md shouldContain "![fərq]($visual.png)"
        ComparisonMarkdownWriter().render(comparison(VisualGate.REPORT, unchanged)).let {
            it shouldContain "| public-look | /haqqimizda | planşet | dəyişməyib | — |"
            it shouldNotContain "![fərq]"
        }
        md.lines().count { it.startsWith("## Görünüş") } shouldBe 1
    }
}
