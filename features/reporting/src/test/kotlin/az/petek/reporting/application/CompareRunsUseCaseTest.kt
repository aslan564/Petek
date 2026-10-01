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

package az.petek.reporting.application

import az.petek.core.ids.RunId
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.reporting.ReportTestData.START
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.run
import az.petek.reporting.ReportTestData.step
import az.petek.reporting.application.CompareRunsUseCase.Baseline
import az.petek.reporting.domain.ComparisonRefusedException
import az.petek.reporting.domain.ComparisonRefusedException.Reason
import az.petek.reporting.domain.StepChange
import az.petek.reporting.infrastructure.ComparisonHtmlWriter
import az.petek.reporting.infrastructure.ComparisonMarkdownWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CompareRunsUseCaseTest {
    @TempDir
    lateinit var dir: Path

    private val evidence = InMemoryEvidence()

    private fun useCase() =
        CompareRunsUseCase(evidence, evidence, InMemoryArtifactStore(dir), listOf(ComparisonHtmlWriter(), ComparisonMarkdownWriter()))

    /** A finished run of `portal-core` [minutes] after the start, whose step `read` [passes] or the site fails. */
    private suspend fun recorded(
        id: String,
        minutes: Long,
        passes: Boolean,
        release: String? = null,
        name: String = "portal-core",
        group: String? = null,
        result: RunResult = RunResult.PASSED,
    ): RunId {
        val runId = RunId(id)
        val startedAt = START.plusSeconds(minutes * 60)
        evidence.create(
            run(runId, startedAt = startedAt, result = result, campaignName = name, repeatGroup = group).copy(release = release),
        )
        evidence.step(step("join", "a01", runId = runId))
        evidence.step(step("read", "a02", runId = runId))
        evidence.assertion(assertion("read", "a02", EvidenceSource.RECEIVER, if (passes) Verdict.PASSED else Verdict.FAILED, runId = runId))
        return runId
    }

    @Test
    fun `the latest earlier run of the scenario is the baseline, and the comparison is written beside the report`() =
        runBlocking<Unit> {
            recorded("run_1", 0, passes = true, release = "v1.4.0")
            recorded("run_2", 10, passes = true, release = "v1.4.1")
            recorded("run_other", 15, passes = true, name = "another-scenario")
            val current = recorded("run_3", 20, passes = false, release = "v1.4.2")

            val result = useCase().compare(current)

            result.comparison.baseline.runId shouldBe RunId("run_2")
            result.comparison.steps.associate { it.scenarioStep to it.change } shouldBe
                mapOf("join" to StepChange.UNCHANGED, "read" to StepChange.NEW_FAILURE)
            result.comparison.regressed shouldBe true
            result.files.map { dir.relativize(it).toString() } shouldContainExactly
                listOf("run_3/report/compare-run_2.html", "run_3/report/compare-run_2.md")
            Files.readString(result.files[0]).let {
                it shouldContain "Versiyaların müqayisəsi: portal-core"
                it shouldContain "run_2 (versiya v1.4.1"
                it shouldContain "yeni sınıb"
                it shouldContain "href=\"../../run_2/report/index.html\""
            }
            Files.readString(result.files[1]) shouldContain "| read | yeni sınıb |"
        }

    @Test
    fun `a named release or run is the baseline when asked for`() =
        runBlocking<Unit> {
            recorded("run_1", 0, passes = false, release = "v1.4.0")
            recorded("run_2", 10, passes = true, release = "v1.4.1")
            val current = recorded("run_3", 20, passes = true, release = "v1.4.2")

            val byReleaseResult = useCase().compare(current, Baseline.Release("v1.4.0"))
            val byRunResult = useCase().compare(current, Baseline.Run(RunId("run_2")))
            val byRelease = byReleaseResult.comparison
            val byRun = byRunResult.comparison

            // Each pair keeps its own page: comparing with another baseline never overwrites the first one.
            Files.readString(byReleaseResult.files[0]) shouldContain "run_1 (versiya v1.4.0"
            Files.readString(byRunResult.files[0]) shouldContain "run_2 (versiya v1.4.1"

            byRelease.baseline.runId shouldBe RunId("run_1")
            byRelease.fixed.map { it.scenarioStep } shouldContainExactly listOf("read")
            byRun.baseline.runId shouldBe RunId("run_2")
            byRun.regressed shouldBe false
        }

    @Test
    fun `a run of the same repeat group is never its baseline`() =
        runBlocking<Unit> {
            recorded("run_1", 0, passes = true)
            recorded("run_2", 10, passes = false, group = "grp_1")
            val current = recorded("run_3", 20, passes = false, group = "grp_1")

            useCase()
                .compare(current)
                .comparison.baseline.runId shouldBe RunId("run_1")
        }

    @Test
    fun `runs that cannot be compared are refused with the reason`() =
        runBlocking<Unit> {
            val alone = recorded("run_1", 0, passes = true)

            fun reason(block: suspend () -> Unit) = runBlocking { shouldThrow<ComparisonRefusedException> { block() }.reason }

            reason { useCase().compare(alone) } shouldBe Reason.NO_BASELINE
            reason { useCase().compare(alone, Baseline.Release("v9")) } shouldBe Reason.NO_BASELINE
            reason { useCase().compare(alone, Baseline.Run(alone)) } shouldBe Reason.SAME_RUN
            val other = recorded("run_other", 5, passes = true, name = "another-scenario")
            reason { useCase().compare(alone, Baseline.Run(other)) } shouldBe Reason.OTHER_SCENARIO
            val going = recorded("run_2", 10, passes = true, result = RunResult.RUNNING)
            reason { useCase().compare(going) } shouldBe Reason.NOT_FINISHED
        }
}
