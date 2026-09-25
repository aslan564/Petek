/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.reporting.ReportTestData
import az.petek.reporting.domain.StepRow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MarkdownReportWriterTest {
    @TempDir
    lateinit var root: Path

    private val writer = MarkdownReportWriter()

    @Test
    fun `writes report md into the report directory and creates it when missing`() {
        val directory = root.resolve("run_test").resolve("report")

        val file = writer.write(SampleReport.model(), directory)

        file shouldBe directory.resolve("report.md")
        file.shouldExist()
        Files.readString(file) shouldBe writer.render(SampleReport.model())
    }

    @Test
    fun `the header and summary carry the run and its key numbers`() {
        val md = writer.render(SampleReport.model())

        md shouldStartWith "# Pətək hesabatı: kadrohr-core\n"
        md shouldContain "| Run | run_test |"
        md shouldContain "| Hədəf | https://staging.kadrohr.test |"
        md shouldContain "| Başladı | 2026-01-01 10:00:00 UTC |"
        md shouldContain "| Nəticə | keçmədi |"
        md shouldContain "| Təkrar qrupu | grp_1 (#2) |"
        md shouldContain "## Xülasə"
        md shouldContain "| Keçən addımlar | 57 |"
        md shouldContain "| Keçməyən addımlar | 3 |"
        md shouldContain "| Assertlər | 88 keçdi · 4 keçmədi · 2 ötürüldü |"
        md shouldContain "| Tapıntılar | 2 |"
        md shouldContain "| Agentlər | 30 |"
        md shouldContain "| Müddət | 4 dəq 05 san |"
        md shouldContain "| Tokenlər | giriş 1 234 567 · çıxış 89 012 |"
        md shouldContain "| Xərc | $0.0420 |"
        md shouldContain "| Real-time nəqliyyat | SSE, POLLING |"
    }

    @Test
    fun `findings show A B C side by side with the note and evidence links`() {
        val md = writer.render(SampleReport.model())

        md shouldContain "## Tapıntılar (2)"
        md shouldContain "### 1. Çatdırılma / UI xətası · read_announce · a17"
        md shouldContain "| A: göndərən | — |"
        md shouldContain "| B: alan | Sabah 10:00 ümumi iclas -> (none) |"
        md shouldContain "| C: oracle | published -> published |"
        md shouldContain "Qeyd: The target (C) confirms the change"
        md shouldContain "Sübut: [0001-screenshot.png](<../a01/0001-screenshot.png>), [0002-oracle.json](<../a17/0002-oracle.json>)\n"
        md shouldContain "### 2. Backend xətası · join · Vəli Həsənov (a07)"
        md shouldContain "| C: oracle | no e-mail sent |"
    }

    @Test
    fun `the step table shows every step with agent result duration and screenshot`() {
        val md = writer.render(SampleReport.model())

        md shouldContain "## Addımlar (3)"
        md shouldContain
            "| announce | Əli Məmmədov (a01) | do | keçdi | 4,2 san | — | [0001-screenshot.png](<../a01/0001-screenshot.png>) |"
        md shouldContain "| join | Vəli Həsənov (a07) | run | keçmədi | 1 dəq 01 san | mail_timeout: no e-mail | — |"
        md shouldContain "| read_announce | a17 | wait | ilişdi | 2 dəq 00 san | — | — |"
    }

    @Test
    fun `the latency table shows average p95 max and who never saw the event`() {
        val md = writer.render(SampleReport.model())

        md shouldContain "## Real-time gecikmə"
        md shouldContain "| announcement_created \\#42 | 29 | 28 | 812 ms | 1450 ms | 2210 ms | a17 |"
        md shouldContain "- **announcement_created \\#42**: a02 640 ms, a17 —"
    }

    @Test
    fun `stability failed agents and usage are reported`() {
        val md = writer.render(SampleReport.model())

        md shouldContain "## Stabillik"
        md shouldContain "| announce | 3 | 3 | 100% | sabit |"
        md shouldContain "| read_announce | 3 | 2 | 67% | flaky |"
        md shouldContain "## Uğursuz agentlər (1)"
        md shouldContain "| a07 | Vəli Həsənov | join | mail_timeout |"
        md shouldContain "## İstifadə: token və xərc"
        md shouldContain "| a01 | 1 000 000 | 80 000 | 1 500 | 12 | $0.0400 |"
        md shouldContain "| a02 | 234 567 | 9 012 | 100 | 4 | $0.0020 |"
        md shouldContain "| **Cəmi** | 1 234 567 | 89 012 | 1 600 | 16 | $0.0420 |"
        md.indexOf("| a01 | 1 000 000") shouldBeLessThan md.indexOf("| a02 | 234 567")
    }

    @Test
    fun `usage rows follow the agent number, so a100 comes after a99`() {
        val usage = listOf("a100", "a99", "a1000", "a02").map { ReportTestData.usage(it, input = 10, output = 1, cost = null) }

        val md = writer.render(SampleReport.model().copy(usage = usage))

        val rows = listOf("| a02 |", "| a99 |", "| a100 |", "| a1000 |").map(md::indexOf)
        rows.forEach { it shouldBeGreaterThan -1 }
        rows shouldBe rows.sorted()
    }

    @Test
    fun `the stability section is left out for a single run`() {
        writer.render(SampleReport.model(stability = null)) shouldNotContain "Stabillik"
    }

    @Test
    fun `links with a scheme are never written`() {
        writer.render(SampleReport.model()) shouldNotContain "javascript"
    }

    @Test
    fun `absolute network and backslash links are never written`() {
        val model = SampleReport.model()
        val odd =
            model.copy(
                artifactLinks = mapOf("art_1" to "\\\\attacker\\x.png", "art_2" to "//attacker/x", "art_evil" to "/etc/passwd"),
            )

        val md = writer.render(odd)

        md shouldNotContain "attacker"
        md shouldNotContain "/etc/passwd"
    }

    @Test
    fun `a refusal the forbidden step expected is shown as refused not as stuck`() {
        val model = SampleReport.model()
        val refused = StepRow("forbidden", "a12", null, "DO", "BLOCKED", 3_000, "permission_denied: no approve button", null)

        val md = writer.render(model.copy(steps = listOf(refused)))

        md shouldContain "| forbidden | a12 | do | icazə verilmədi | 3,0 san | permission_denied: no approve button | — |"
    }

    @Test
    fun `an actor that lost a race is shown as such, not as passed or failed`() {
        val model = SampleReport.model()
        val lost =
            StepRow(
                "race",
                "a03",
                null,
                "DO",
                "PASSED",
                2_000,
                "lost_race: POST /tickets/t2/approve -> 409; won by a02",
                null,
                lostRace = true,
            )
        val report = StepRow("race", "a03", null, "DO", "FAILED", 1_000, "problem_reported: already decided", null, lostRace = true)

        val md = writer.render(model.copy(steps = listOf(lost, report)))

        md shouldContain "| race | a03 | do | yarışı uduzdu | 2,0 san | lost_race: POST /tickets/t2/approve -> 409; won by a02 | — |"
        md shouldContain "| race | a03 | do | yarışı uduzdu | 1,0 san | problem_reported: already decided | — |"
    }

    @Test
    fun `untrusted text cannot break a table or inject markup`() {
        val md =
            writer.render(
                SampleReport.model(
                    stepDetail = "a | b ${SampleReport.SCRIPT}\nsecond line [x](http://evil) `code` *bold*",
                    agentName = "Eve <img src=x onerror=alert(1)>",
                ),
            )

        md shouldContain "a \\| b \\<script>alert('x')\\</script> second line \\[x\\](http://evil) \\`code\\` \\*bold\\*"
        md shouldContain "Eve \\<img src=x onerror=alert(1)> (a01)"
        // Every `<` of the untrusted text is backslash-escaped, so no tag can open.
        Regex("""(?<!\\)<(script|img)""").containsMatchIn(md) shouldBe false
    }

    @Test
    fun `empty sections say so instead of printing empty tables`() {
        val empty =
            SampleReport.model().copy(
                steps = emptyList(),
                latency = emptyList(),
                findings = emptyList(),
                failedAgents = emptyList(),
                usage = emptyList(),
                stability = emptyList(),
            )

        val md = writer.render(empty)

        md shouldContain "Tapıntı yoxdur"
        md shouldContain "Addım qeydə alınmayıb."
        md shouldContain "Real-time hadisəsi olmayıb."
        md shouldContain "Təkrar qrupunda addım yoxdur."
        md shouldContain "Bütün agentlər addımlarını tamamladı."
        md shouldContain "| **Cəmi** | 1 234 567 | 89 012 | 0 | 0 | $0.0420 |"
    }
}
