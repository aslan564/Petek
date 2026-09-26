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

package az.petek.reporting.domain

import az.petek.core.ids.RunId
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StabilityAnalyzerTest {
    private val analyzer = StabilityAnalyzer()

    private fun evidence(
        index: Int,
        steps: List<StepRecord> = emptyList(),
        assertions: List<AssertionRecord> = emptyList(),
    ): RepeatRunEvidence {
        val runId = RunId("run_$index")
        return RepeatRunEvidence(
            runId,
            assertions.map { it.copy(runId = runId) },
            steps.map { it.copy(runId = runId) },
        )
    }

    private fun visible(
        agent: String,
        verdict: Verdict,
    ) = assertion("read_announce", agent, EvidenceSource.RECEIVER, verdict)

    @Test
    fun `a step that passes in every run is stable`() {
        val runs =
            (1..3).map {
                evidence(it, listOf(step("announce", "a01")), listOf(assertion("announce", "a01", EvidenceSource.ORACLE, Verdict.PASSED)))
            }

        val row = analyzer.analyze(runs).single()

        row shouldBe StabilityRow("announce", runs = 3, passed = 3)
        row.flaky shouldBe false
        row.passRate shouldBe 1.0
    }

    @Test
    fun `a step whose assertions fail in one of three runs is flaky`() {
        val runs =
            listOf(
                evidence(1, assertions = listOf(visible("a02", Verdict.PASSED), visible("a03", Verdict.PASSED))),
                evidence(2, assertions = listOf(visible("a02", Verdict.PASSED), visible("a03", Verdict.FAILED))),
                evidence(3, assertions = listOf(visible("a02", Verdict.PASSED), visible("a03", Verdict.PASSED))),
            )

        val row = analyzer.analyze(runs).single()

        row.passed shouldBe 2
        row.flaky shouldBe true
        row.passRate shouldBe (2.0 / 3 plusOrMinus 1e-9)
    }

    @Test
    fun `a failed errored or blocked step record fails the step in that run`() {
        val runs =
            listOf(
                evidence(1, steps = listOf(step("join", "a02", StepStatus.FAILED, StepKind.RUN))),
                evidence(2, steps = listOf(step("join", "a02", StepStatus.ERROR, StepKind.RUN))),
                evidence(3, steps = listOf(step("join", "a02", StepStatus.BLOCKED, StepKind.RUN))),
                evidence(4, steps = listOf(step("join", "a02", StepStatus.PASSED, StepKind.RUN))),
            )

        analyzer.analyze(runs).single() shouldBe StabilityRow("join", runs = 4, passed = 1)
    }

    @Test
    fun `one failing agent is enough to fail the step in that run`() {
        val runs =
            listOf(
                evidence(1, steps = listOf(step("join", "a02"), step("join", "a03", StepStatus.FAILED))),
                evidence(2, steps = listOf(step("join", "a02"), step("join", "a03"))),
            )

        analyzer.analyze(runs).single() shouldBe StabilityRow("join", runs = 2, passed = 1)
    }

    @Test
    fun `skipped assertions and skipped steps next to passing evidence do not fail a step`() {
        val runs =
            listOf(
                evidence(
                    1,
                    steps = listOf(step("announce", "a01"), step("announce", "a09", StepStatus.SKIPPED, StepKind.SYSTEM)),
                    assertions = listOf(assertion("announce", "a01", EvidenceSource.ORACLE, Verdict.SKIPPED)),
                ),
                evidence(2, steps = listOf(step("announce", "a01"))),
            )

        analyzer.analyze(runs).single() shouldBe StabilityRow("announce", runs = 2, passed = 2)
    }

    @Test
    fun `a step that was only skipped in a run did not pass there`() {
        val runs =
            listOf(
                evidence(
                    1,
                    steps = listOf(step("read_announce", null, StepStatus.SKIPPED, StepKind.SYSTEM)),
                    assertions = listOf(assertion("read_announce", "a02", EvidenceSource.ORACLE, Verdict.SKIPPED)),
                ),
                evidence(2, steps = listOf(step("read_announce", "a02")), assertions = listOf(visible("a02", Verdict.PASSED))),
            )

        val row = analyzer.analyze(runs).single()

        row shouldBe StabilityRow("read_announce", runs = 2, passed = 1)
        row.flaky shouldBe true
    }

    @Test
    fun `a forbidden action the target refused passes when its assertions pass`() {
        val runs =
            (1..2).map {
                evidence(
                    it,
                    steps = listOf(step("forbidden", "a12", StepStatus.BLOCKED, detail = "permission_denied: no approve button")),
                    assertions = listOf(assertion("forbidden", "a12", EvidenceSource.HARNESS, Verdict.PASSED, type = "http_status")),
                )
            }

        analyzer.analyze(runs).single() shouldBe StabilityRow("forbidden", runs = 2, passed = 2)
    }

    @Test
    fun `a step missing from a run did not pass there`() {
        val runs =
            listOf(
                evidence(1, steps = listOf(step("announce", "a01"), step("ticket", "a05"))),
                evidence(2, steps = listOf(step("announce", "a01"))),
            )

        analyzer.analyze(runs) shouldContainExactly
            listOf(StabilityRow("announce", 2, 2), StabilityRow("ticket", 2, 1))
    }

    @Test
    fun `a step that always fails is not flaky`() {
        val runs = (1..3).map { evidence(it, assertions = listOf(visible("a02", Verdict.FAILED))) }

        val row = analyzer.analyze(runs).single()

        row.passed shouldBe 0
        row.flaky shouldBe false
    }

    @Test
    fun `rows follow the order in which steps first appear across the group`() {
        val runs =
            listOf(
                evidence(
                    1,
                    steps = listOf(step("join", "a02"), step("announce", "a01")),
                    assertions = listOf(visible("a02", Verdict.PASSED)),
                ),
                evidence(2, steps = listOf(step("join", "a02"), step("race", "a03"), step("announce", "a01"))),
            )

        analyzer.analyze(runs).map { it.scenarioStep } shouldContainExactly listOf("join", "announce", "read_announce", "race")
    }

    @Test
    fun `an empty group has no rows`() {
        analyzer.analyze(emptyList()).shouldBeEmpty()
    }
}
