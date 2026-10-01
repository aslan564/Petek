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

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.PageTimingRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.reporting.ReportTestData.START
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.event
import az.petek.reporting.ReportTestData.receipt
import az.petek.reporting.ReportTestData.run
import az.petek.reporting.ReportTestData.step
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.LookKey
import az.petek.reporting.domain.visual.LookReason
import az.petek.reporting.domain.visual.VisualGate
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class RunComparerTest {
    private val before = RunId("run_before")
    private val after = RunId("run_after")

    private fun evidence(
        runId: RunId,
        steps: List<StepRecord> = emptyList(),
        checks: List<az.petek.evidence.domain.AssertionRecord> = emptyList(),
        events: List<EventRecord> = emptyList(),
        receipts: List<EventReceipt> = emptyList(),
        hash: String = "c0ffee",
    ) = ComparedEvidence(run(runId).copy(campaignHash = hash), steps, checks, events, receipts)

    private fun passed(
        runId: RunId,
        vararg steps: String,
    ) = steps.map { step(it, "a01", runId = runId) }

    private fun siteFailed(
        runId: RunId,
        step: String,
    ) = assertion(step, "a01", EvidenceSource.RECEIVER, Verdict.FAILED, runId = runId, note = "not shown")

    private fun byStep(comparison: RunComparison) = comparison.steps.associate { it.scenarioStep to it.change }

    @Test
    fun `a step the site passed before and fails now is a regression, the other way round a fix`() {
        val comparison =
            RunComparer().compare(
                evidence(
                    before,
                    passed(before, "join", "announce", "read") + passed(before, "ticket"),
                    listOf(siteFailed(before, "ticket")),
                ),
                evidence(after, passed(after, "join", "announce", "read", "ticket"), listOf(siteFailed(after, "read"))),
            )

        byStep(comparison) shouldBe
            mapOf(
                "join" to StepChange.UNCHANGED,
                "announce" to StepChange.UNCHANGED,
                "read" to StepChange.NEW_FAILURE,
                "ticket" to StepChange.FIXED,
            )
        comparison.newFailures.map { it.scenarioStep } shouldContainExactly listOf("read")
        comparison.regressed shouldBe true
    }

    @Test
    fun `a race the site lets two racers win in the new release is a regression, not a lost agent`() {
        fun raced(
            runId: RunId,
            winners: Int,
        ): ComparedEvidence {
            val verdict = if (winners == 1) Verdict.PASSED else Verdict.FAILED
            val status = if (winners == 1) StepStatus.PASSED else StepStatus.FAILED
            return evidence(
                runId,
                listOf(
                    step("approve", "a02", stepId = "stp_approve_a02", runId = runId),
                    step("approve", "a03", stepId = "stp_approve_a03", runId = runId),
                    step(
                        "approve",
                        null,
                        status,
                        StepKind.SYSTEM,
                        detail = "only_one_succeeds: ${verdict.name} ($winners succeeded)",
                        action = "verify_group only_one_succeeds",
                        runId = runId,
                    ),
                ),
                listOf(assertion("approve", null, EvidenceSource.HARNESS, verdict, type = "only_one_succeeds", runId = runId)),
            )
        }

        val comparison = RunComparer().compare(raced(before, winners = 1), raced(after, winners = 2))

        comparison.steps.single().change shouldBe StepChange.NEW_FAILURE
        comparison.regressed shouldBe true
    }

    @Test
    fun `what a lost tester or the surroundings did is never a change of the site`() {
        val lost = step("read", "a02", StepStatus.ERROR, detail = "llm_failed: the AI provider did not answer", runId = after)
        val comparison =
            RunComparer().compare(
                evidence(before, passed(before, "read", "ticket") + step("wait", "a03", StepStatus.SKIPPED, runId = before)),
                evidence(after, listOf(lost) + passed(after, "ticket"), listOf(siteFailed(after, "wait"))),
            )

        byStep(comparison) shouldBe
            mapOf("read" to StepChange.NOT_COMPARABLE, "ticket" to StepChange.UNCHANGED, "wait" to StepChange.FAILING)
        comparison.regressed shouldBe false
    }

    @Test
    fun `with another scenario a step found in one run only is added or removed, with the same one it was not reached`() {
        val changed =
            RunComparer().compare(
                evidence(before, passed(before, "join", "old"), hash = "aaa"),
                evidence(after, passed(after, "join", "new"), hash = "bbb"),
            )
        val same =
            RunComparer().compare(
                evidence(before, passed(before, "join", "late")),
                evidence(after, passed(after, "join")),
            )

        changed.scenarioChanged shouldBe true
        byStep(changed) shouldBe mapOf("join" to StepChange.UNCHANGED, "new" to StepChange.ADDED, "old" to StepChange.REMOVED)
        byStep(same) shouldBe mapOf("join" to StepChange.UNCHANGED, "late" to StepChange.NOT_COMPARABLE)
    }

    @Test
    fun `a deterministic step is slower when its median time grew by a quarter and by 300 ms, an AI step never counts`() {
        fun timed(
            runId: RunId,
            ms: Long,
        ) = listOf("a01", "a02", "a03").map { agent ->
            step("join", agent, kind = StepKind.RUN, durationMs = ms, stepId = "stp_join_$agent", runId = runId)
        } + step("look", "a01", kind = StepKind.DO, durationMs = ms * 10, runId = runId)

        val slower = RunComparer().compare(evidence(before, timed(before, 1_000)), evidence(after, timed(after, 1_400)))
        val noise = RunComparer().compare(evidence(before, timed(before, 100)), evidence(after, timed(after, 300)))
        val faster = RunComparer().compare(evidence(before, timed(before, 2_000)), evidence(after, timed(after, 1_000)))

        slower.steps.single { it.scenarioStep == "join" }.let {
            it.beforeMs shouldBe 1_000
            it.afterMs shouldBe 1_400
            it.speed shouldBe SpeedChange.SLOWER
        }
        slower.steps.single { it.scenarioStep == "look" }.speed shouldBe null
        slower.regressed shouldBe true
        noise.slowerSteps.shouldBeEmpty()
        // A step that failed at once before and passes in its normal time now is fixed, never slower.
        val fixed =
            RunComparer().compare(
                evidence(before, timed(before, 200), listOf(siteFailed(before, "join"))),
                evidence(after, timed(after, 1_500)),
            )
        fixed.steps.single { it.scenarioStep == "join" }.let {
            it.change shouldBe StepChange.FIXED
            it.speed shouldBe null
        }
        fixed.regressed shouldBe false
        faster.steps.single { it.scenarioStep == "join" }.speed shouldBe SpeedChange.FASTER
        faster.regressed shouldBe false
    }

    @Test
    fun `a page that loads later or shows its main content later on a screen is slower, one that jumps more is worse`() {
        fun timed(
            runId: RunId,
            agent: String,
            page: String,
            load: Long,
            paint: Long?,
            shift: Double,
            device: String? = "phone",
        ) = PageTimingRecord(runId, StepId("stp_$agent"), AgentId(agent), "public-pages", page, device, 100, 300, load, paint, shift, START)

        val comparison =
            RunComparer().compare(
                evidence(before).copy(
                    pageTimings =
                        listOf(
                            timed(before, "a01", "/", 900, 1_000, 0.01),
                            timed(before, "a02", "/", 1_100, 1_200, 0.01),
                            timed(before, "a01", "/about", 600, null, 0.02),
                            timed(before, "a01", "/news", 800, 900, 0.05),
                            timed(before, "a01", "/", 900, 1_000, 0.0, device = "desktop"),
                        ),
                ),
                evidence(after).copy(
                    pageTimings =
                        listOf(
                            timed(after, "a01", "/", 900, 2_400, 0.01),
                            timed(after, "a02", "/", 1_000, 2_600, 0.01),
                            timed(after, "a01", "/about", 620, null, 0.02),
                            timed(after, "a01", "/news", 800, 900, 0.3),
                            timed(after, "a01", "/", 1_000, 1_000, 0.0, device = "desktop"),
                        ),
                ),
            )

        comparison.pages.associate { (it.page to it.device) to (it.speed to it.shiftGrew) } shouldBe
            mapOf(
                ("/" to "phone") to (SpeedChange.SLOWER to false),
                ("/about" to "phone") to (null to false),
                ("/news" to "phone") to (null to true),
                ("/" to "desktop") to (null to false),
            )
        comparison.pages.first().let {
            it.beforePaintMs shouldBe 1_000
            it.afterPaintMs shouldBe 2_400
        }
        comparison.worsePages.map { it.page } shouldContainExactly listOf("/", "/news")
        comparison.regressed shouldBe true
    }

    @Test
    fun `real-time delivery is compared per event name over every receiver, by its p95`() {
        fun delivered(
            runId: RunId,
            vararg latencies: Long,
        ) = latencies.mapIndexed { i, ms -> receipt("evt_${runId.value}", "a0${i + 2}", ms, runId = runId) }

        val comparison =
            RunComparer(SpeedThresholds(ratio = 0.25, atLeast = Duration.ofMillis(300))).compare(
                evidence(
                    before,
                    events = listOf(event("evt_run_before", objectId = "41", runId = before)),
                    receipts = delivered(before, 400, 500, 600, 700),
                ),
                evidence(
                    after,
                    events = listOf(event("evt_run_after", objectId = "97", runId = after)),
                    receipts = delivered(after, 500, 900, 1_400, 2_100),
                ),
            )

        comparison.deliveries.single().let {
            it.event shouldBe "announcement_created"
            it.beforeP95Ms shouldBe 700
            it.afterP95Ms shouldBe 2_100
            it.speed shouldBe SpeedChange.SLOWER
        }
        comparison.regressed shouldBe true
    }

    /** Both runs passing every step, with [looks] attached the way the reporting application attaches them. */
    private fun withLooks(
        gate: VisualGate,
        vararg looks: LookChange,
    ) = RunComparer()
        .compare(evidence(before, passed(before, "public-look")), evidence(after, passed(after, "public-look")))
        .copy(
            looks =
                looks.mapIndexed { i, change ->
                    LookComparison(
                        i + 1,
                        LookKey("public-look", "/page-$i", "phone"),
                        change,
                        LookReason.KEPT_MOVING.takeIf {
                            change == LookChange.NOT_COMPARABLE
                        },
                    )
                },
            visualGate = gate,
        )

    @Test
    fun `a changed look is shown but does not make the comparison worse under the report gate`() {
        val comparison = withLooks(VisualGate.REPORT, LookChange.CHANGED, LookChange.UNCHANGED)

        comparison.changedLooks.map { it.key.page } shouldContainExactly listOf("/page-0")
        comparison.regressed shouldBe false
    }

    @Test
    fun `a changed look makes the comparison worse under the fail gate`() {
        withLooks(VisualGate.FAIL, LookChange.CHANGED, LookChange.UNCHANGED).regressed shouldBe true
        withLooks(VisualGate.FAIL, LookChange.UNCHANGED, LookChange.ADDED, LookChange.REMOVED).regressed shouldBe false
    }

    @Test
    fun `looks that are not comparable never make it worse`() {
        val comparison = withLooks(VisualGate.FAIL, LookChange.NOT_COMPARABLE, LookChange.NOT_COMPARABLE)

        comparison.incomparableLooks.size shouldBe 2
        comparison.regressed shouldBe false
    }
}
