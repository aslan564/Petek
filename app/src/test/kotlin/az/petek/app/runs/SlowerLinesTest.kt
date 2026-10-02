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

package az.petek.app.runs

import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.evidence.domain.RunRecord
import az.petek.reporting.domain.PageComparison
import az.petek.reporting.domain.RunComparison
import az.petek.reporting.domain.SpeedChange
import az.petek.reporting.domain.SpeedThresholds
import az.petek.reporting.domain.StepChange
import az.petek.reporting.domain.StepComparison
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class SlowerLinesTest {
    private fun run(id: String) = RunRecord(RunId(id), RunTag("k7x2"), "c0ffee", "greet", 7, "https://staging.example.com", Instant.EPOCH)

    private val comparison =
        RunComparison(
            baseline = run("run_1"),
            current = run("run_2"),
            scenarioChanged = false,
            steps = listOf(StepComparison("join", StepChange.UNCHANGED, 1_000, 1_400, SpeedChange.SLOWER)),
            deliveries = emptyList(),
            thresholds = SpeedThresholds(),
            pages =
                listOf(
                    PageComparison("/", "phone", 900, 1_400, null, null, 0.01, 0.01, SpeedChange.SLOWER),
                    PageComparison("/news", null, null, null, 900, null, 0.05, 0.3, shiftGrew = true),
                ),
        )

    @Test
    fun `a page measure neither run reported is left out and one only one run reported shows a dash, never null`() {
        SlowerLines.CLI.of(comparison) shouldContainExactly
            listOf(
                "join 1000 ms → 1400 ms",
                "/ (phone): load 900 ms → 1400 ms, CLS 0.01 → 0.01",
                "/news: LCP 900 ms → —, CLS 0.05 → 0.30",
            )
        SlowerLines.PANEL.of(comparison)[1] shouldBe "/ (phone): yüklənmə 900 ms → 1400 ms, CLS 0,01 → 0,01"
    }
}
