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

import az.petek.core.ids.CorrelationId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.run
import az.petek.reporting.ReportTestData.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The records the orchestrator and the agents write for a race between a02 (winner) and a03 (loser), as in the two
 * fake-target runs that found the problem: a03's agent either claimed success (run 1) or reported the ticket as
 * already decided (run 2). Both are an expected outcome, never a failed agent.
 */
class ExpectedOutcomesTest {
    private fun action(
        agent: String,
        status: StepStatus,
        detail: String?,
        id: String,
        kind: StepKind = StepKind.DO,
        action: String = "do race",
    ): StepRecord = step("race", agent, status, kind, detail, action, stepId = id).copy(correlationId = CorrelationId("cor_race_$agent"))

    private val winner =
        listOf(
            action("a02", StepStatus.PASSED, "Clicked [4]", "a02_turn1", action = "click [4] \"Təsdiqlə\""),
            action("a02", StepStatus.PASSED, "done | outcome: SUCCEEDED: Approved", "a02_turn2", action = "done"),
            action("a02", StepStatus.PASSED, "Approved; request: POST /tickets/t2/approve -> 303", "a02_summary"),
        )

    /** Run 2: the loser's agent reported the ticket as already decided. */
    private val reportedLoser =
        listOf(
            action("a03", StepStatus.PASSED, "Clicked [4]", "a03_turn1", action = "click [4] \"Təsdiqlə\""),
            action(
                "a03",
                StepStatus.FAILED,
                "Problem reported: other: Bu müraciət artıq qərarlaşdırılıb | outcome: FAILED problem_reported: other: " +
                    "Bu müraciət artıq qərarlaşdırılıb",
                "a03_turn2",
                action = "report_problem other \"Bu müraciət artıq qərarlaşdırılıb\"",
            ),
            action(
                "a03",
                StepStatus.PASSED,
                "lost_race: POST /tickets/t2/approve -> 409; won by a02; agent: other: Bu müraciət artıq qərarlaşdırılıb",
                "a03_summary",
            ),
        )

    /** Run 1: the loser's agent claimed success although the target answered 409. */
    private val claimingLoser =
        listOf(
            action("a03", StepStatus.PASSED, "done | outcome: SUCCEEDED: Approved", "a03_turn1", action = "done"),
            action("a03", StepStatus.PASSED, "lost_race: POST /tickets/t2/approve -> 409; won by a02; agent: Approved", "a03_summary"),
        )

    private val group = step("race", null, StepStatus.PASSED, StepKind.SYSTEM, "only_one_succeeds: PASSED", stepId = "verify_group")

    @Test
    fun `every record of the lost action is expected and none is a failure`() {
        val steps = winner + reportedLoser + group
        val expected = ExpectedOutcomes(steps)

        reportedLoser.map { expected.isLostRace(it) } shouldBe listOf(true, true, true)
        reportedLoser.map { expected.isExpected(it) } shouldBe listOf(true, true, true)
        steps.filter(expected::isFailure).shouldBeEmpty()
        steps.mapNotNull(expected::failureKey).shouldBeEmpty()
        winner.none { expected.isLostRace(it) } shouldBe true
        expected.isLostRace(group) shouldBe false
    }

    @Test
    fun `without the orchestrator's lost_race record the agent's report stays a failure`() {
        val expected = ExpectedOutcomes(winner + reportedLoser.dropLast(1))

        expected.isFailure(reportedLoser[1]) shouldBe true
        expected.failureKey(reportedLoser[1]) shouldBe "problem_reported"
    }

    @Test
    fun `a wait or a verification error of the losing actor is judged on its own`() {
        val waitTimeout = action("a03", StepStatus.FAILED, "not_received: ticket_created", "a03_wait", StepKind.WAIT)
        val verifyError = action("a03", StepStatus.ERROR, "verification failed: IOException", "a03_verify", StepKind.ASSERT)
        val expected = ExpectedOutcomes(reportedLoser + waitTimeout + verifyError)

        expected.isFailure(waitTimeout) shouldBe true
        expected.isFailure(verifyError) shouldBe true
    }

    @Test
    fun `another actor's failure in the same step is still a failure`() {
        val crashed = action("a04", StepStatus.ERROR, "browser_error: page crashed", "a04_summary")
        val expected = ExpectedOutcomes(reportedLoser + crashed)

        expected.isFailure(crashed) shouldBe true
        expected.failureKey(crashed) shouldBe "browser_error"
    }

    @Test
    fun `a lost_race key on a record that did not pass is not a lost race`() {
        val odd = action("a03", StepStatus.FAILED, "lost_race: made up by someone", "a03_odd")

        FailureKeys.isLostRace(odd) shouldBe false
        ExpectedOutcomes(listOf(odd)).isFailure(odd) shouldBe true
    }

    @Test
    fun `a passed record that only mentions lost_race does not hide the action's failures`() {
        val mention = action("a03", StepStatus.PASSED, "ok: the page said lost_race somewhere", "a03_summary")
        val crashed = action("a03", StepStatus.ERROR, "browser_error: page crashed", "a03_turn")
        val expected = ExpectedOutcomes(listOf(mention, crashed))

        FailureKeys.isLostRace(mention) shouldBe false
        expected.isLostRace(crashed) shouldBe false
        expected.isFailure(crashed) shouldBe true
        expected.failureKey(crashed) shouldBe "browser_error"
    }

    @Test
    fun `a racer whose success claim its own request refuted is a failure`() {
        val refuted = action("a03", StepStatus.FAILED, "request_failed: POST /tickets/t2/approve -> 500; agent: Approved", "a03_summary")
        val expected = ExpectedOutcomes(winner + refuted)

        expected.isLostRace(refuted) shouldBe false
        expected.isFailure(refuted) shouldBe true
        expected.failureKey(refuted) shouldBe FailureKeys.REQUEST_FAILED
    }

    @Test
    fun `only the orchestrator's record and the agent's failing ones show as a lost race`() {
        val expected = ExpectedOutcomes(reportedLoser)

        reportedLoser.map { expected.showsLostRace(it) } shouldBe listOf(false, true, true)
    }

    @Test
    fun `an expected refusal stays expected`() {
        val refused = step("forbidden", "a06", StepStatus.BLOCKED, detail = "permission_denied: no approve button")

        ExpectedOutcomes(listOf(refused)).isExpected(refused) shouldBe true
        ExpectedOutcomes(listOf(refused)).isFailure(refused) shouldBe false
    }

    /** The real run of 2026-09-26: the agent called the refusal a problem; the orchestrator judged it expected. */
    private fun forbidden(
        status: StepStatus,
        detail: String,
        id: String,
        action: String = "do forbidden",
        kind: StepKind = StepKind.DO,
    ): StepRecord =
        step("forbidden", "a12", status, kind, detail, action, stepId = id).copy(correlationId = CorrelationId("cor_forbidden_a12"))

    private val refusedAsProblem =
        listOf(
            forbidden(StepStatus.PASSED, "OK: opened /tickets.", "a12_turn1", action = "navigate /tickets"),
            forbidden(
                StepStatus.FAILED,
                "Finished: already approved, no approve action for an employee | outcome: FAILED problem_reported: already approved",
                "a12_turn2",
                action = "report_problem other \"already approved\"",
            ),
            forbidden(
                StepStatus.BLOCKED,
                "permission_denied: already approved (agent reported problem_reported; the step expects a refusal, its assertions decide)",
                "a12_summary",
            ),
        )

    @Test
    fun `the agent's own records of an expected refusal are expected too`() {
        val expected = ExpectedOutcomes(refusedAsProblem)

        refusedAsProblem.map { expected.isExpectedRefusal(it) } shouldBe listOf(true, true, true)
        refusedAsProblem.filter(expected::isFailure).shouldBeEmpty()
        refusedAsProblem.mapNotNull(expected::failureKey).shouldBeEmpty()
        refusedAsProblem.map { expected.showsRefusal(it) } shouldBe listOf(false, true, true)
    }

    @Test
    fun `without the orchestrator's refusal record the agent's problem stays a failure`() {
        val expected = ExpectedOutcomes(refusedAsProblem.dropLast(1))

        expected.isFailure(refusedAsProblem[1]) shouldBe true
        expected.failureKey(refusedAsProblem[1]) shouldBe "problem_reported"
    }

    @Test
    fun `a wait of the refused actor is judged on its own`() {
        val waitTimeout =
            forbidden(
                StepStatus.FAILED,
                "not_received: ticket_created",
                "a12_wait",
                action = "wait_for ticket_created",
                kind = StepKind.WAIT,
            )
        val expected = ExpectedOutcomes(refusedAsProblem + waitTimeout)

        expected.isFailure(waitTimeout) shouldBe true
    }

    // --- regressions of the two observed runs, through the judge and stability --------------------------------------

    private val ids: IdGenerator = SequentialIdGenerator()

    @Test
    fun `run 1 - the judge finds nothing when the loser's agent claimed success`() {
        val findings =
            ThreeSourceJudge(ids).findings(
                run(),
                listOf(assertion("race", null, EvidenceSource.SENDER, Verdict.PASSED, type = "only_one_succeeds")),
                winner + claimingLoser + group,
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `run 2 - the judge finds no agent failure when the loser reported the ticket as already decided`() {
        val findings =
            ThreeSourceJudge(ids).findings(
                run(),
                listOf(assertion("race", null, EvidenceSource.SENDER, Verdict.PASSED, type = "only_one_succeeds")),
                winner + reportedLoser + group,
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `a race that the loser lost the same way in every repeat is stable`() {
        val runs =
            listOf(winner + claimingLoser + group, winner + reportedLoser + group).mapIndexed { index, steps ->
                RepeatRunEvidence(
                    RunId("run_$index"),
                    listOf(assertion("race", null, EvidenceSource.SENDER, Verdict.PASSED, type = "only_one_succeeds")),
                    steps,
                )
            }

        StabilityAnalyzer().analyze(runs) shouldContainExactly listOf(StabilityRow("race", runs = 2, passed = 2))
    }

    @Test
    fun `lost_race is a known key that leads the orchestrator's detail`() {
        FailureKeys.find("lost_race: POST /tickets/t2/approve -> 409; won by a02") shouldBe FailureKeys.LOST_RACE
        FailureKeys.of(claimingLoser.last()).shouldBeNull()
    }
}
