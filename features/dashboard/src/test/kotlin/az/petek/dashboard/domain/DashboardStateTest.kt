package az.petek.dashboard.domain

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.dashboard.domain.DashboardUpdate.AgentUpdated
import az.petek.dashboard.domain.DashboardUpdate.AgentsPlanned
import az.petek.dashboard.domain.DashboardUpdate.ArtifactRecorded
import az.petek.dashboard.domain.DashboardUpdate.AssertionRecorded
import az.petek.dashboard.domain.DashboardUpdate.EventRecorded
import az.petek.dashboard.domain.DashboardUpdate.FindingRecorded
import az.petek.dashboard.domain.DashboardUpdate.Message
import az.petek.dashboard.domain.DashboardUpdate.ReceiptRecorded
import az.petek.dashboard.domain.DashboardUpdate.RunCreated
import az.petek.dashboard.domain.DashboardUpdate.RunEnded
import az.petek.dashboard.domain.DashboardUpdate.RunStarted
import az.petek.dashboard.domain.DashboardUpdate.ScenarioStepStarted
import az.petek.dashboard.domain.DashboardUpdate.StepRecorded
import az.petek.dashboard.testing.Records.OTHER_RUN
import az.petek.dashboard.testing.Records.RUN
import az.petek.dashboard.testing.Records.T0
import az.petek.dashboard.testing.Records.a
import az.petek.dashboard.testing.Records.artifact
import az.petek.dashboard.testing.Records.assertion
import az.petek.dashboard.testing.Records.at
import az.petek.dashboard.testing.Records.event
import az.petek.dashboard.testing.Records.finding
import az.petek.dashboard.testing.Records.profile
import az.petek.dashboard.testing.Records.receipt
import az.petek.dashboard.testing.Records.runRecord
import az.petek.dashboard.testing.Records.status
import az.petek.dashboard.testing.Records.step
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class DashboardStateTest {
    private fun board(vararg updates: DashboardUpdate) = DashboardState.EMPTY.applyAll(updates.asList())

    private fun DashboardState.view(seconds: Long = 60) = snapshot(at(seconds))

    private fun DashboardSnapshot.card(agent: Int) = agents.single { it.agentId == a(agent) }

    private val running = RunCreated(runRecord(), at(0))

    @Test
    fun `an empty board waits for a run and counts every agent state as zero`() {
        val view = DashboardState.EMPTY.view()

        view.version shouldBe 0
        view.run.phase shouldBe RunPhase.WAITING
        view.run.runId.shouldBeNull()
        view.run.elapsedMs shouldBe 0
        view.counters.agentsByState shouldBe AgentState.entries.associateWith { 0 }
        view.agents.shouldBeEmpty()
        view.timeline.shouldBeEmpty()
    }

    @Test
    fun `a created run fills the header and its elapsed time follows the monotonic clock`() {
        val state = board(RunCreated(runRecord(name = "Elan axını", target = "https://staging.kadrohr.test"), at(0)))

        val view = state.snapshot(at(seconds = 75, millis = 500))

        view.run.runId shouldBe RUN
        view.run.campaignName shouldBe "Elan axını"
        view.run.target shouldBe "https://staging.kadrohr.test"
        view.run.startedAt shouldBe T0
        view.run.phase shouldBe RunPhase.RUNNING
        view.run.elapsedMs shouldBe 75_500
        view.generatedAt shouldBe T0.plusSeconds(75).plusMillis(500)
    }

    @Test
    fun `a new run starts a fresh board while the version and the timeline order keep growing`() {
        val first =
            board(
                running,
                StepRecorded(step(2), at(1)),
                RunEnded(RUN, RunOutcome.PASSED, 1_000, null, at(2)),
            )

        val second = first.apply(RunCreated(runRecord(runId = OTHER_RUN, name = "İkinci run"), at(3)))
        val view = second.view()

        second.version shouldBe first.version + 1
        view.run.runId shouldBe OTHER_RUN
        view.run.campaignName shouldBe "İkinci run"
        view.run.phase shouldBe RunPhase.RUNNING
        view.agents.shouldBeEmpty()
        view.counters.stepsPassed shouldBe 0
        view.timeline.shouldBeEmpty()
        val entry =
            second
                .apply(Message("salam", at(4)))
                .view()
                .timeline
                .single()
        entry.seq shouldBeGreaterThan first.view().timeline.maxOf { it.seq }
    }

    @Test
    fun `planned agents show their department and registration mode`() {
        val state =
            board(
                running,
                AgentsPlanned(
                    RUN,
                    listOf(
                        profile(1, "Aysel Məmmədova", Role.ADMIN, null, RegistrationMode.OWNER),
                        profile(2, "Əli Kərimov", Role.MANAGER, "Satış", RegistrationMode.INVITE),
                    ),
                    at(1),
                ),
            )

        val view = state.view()

        view.card(1).displayName shouldBe "Aysel Məmmədova"
        view.card(1).role shouldBe Role.ADMIN
        view.card(1).department.shouldBeNull()
        view.card(1).registration shouldBe RegistrationMode.OWNER
        view.card(2).department shouldBe "Satış"
        view.card(2).registration shouldBe RegistrationMode.INVITE
        view.card(2).state shouldBe AgentState.IDLE
    }

    @Test
    fun `a started run puts every agent on the board in agent order`() {
        val state = board(RunStarted(RUN, listOf(status(10), status(2), status(1, role = Role.ADMIN)), at(0)))

        val view = state.view()

        view.run.runId shouldBe RUN
        view.agents.map { it.agentId } shouldContainExactly listOf(a(1), a(2), a(10))
        view.card(1).role shouldBe Role.ADMIN
        view.counters.agents shouldBe 3
        view.counters.agentsByState[AgentState.IDLE] shouldBe 3
    }

    @Test
    fun `the orchestrator's agent state is authoritative and evidence never changes it`() {
        val state =
            board(
                RunStarted(RUN, listOf(status(2)), at(0)),
                AgentUpdated(status(2, AgentState.WORKING, "announce", "do: create the announcement"), at(1)),
                StepRecorded(step(2, status = StepStatus.FAILED, detail = "element_not_found"), at(2)),
            )

        val card = state.view().card(2)

        card.state shouldBe AgentState.WORKING
        card.scenarioStep shouldBe "announce"
        card.failures shouldBe 1
        state.view().counters.agentsByState[AgentState.WORKING] shouldBe 1
    }

    @Test
    fun `a status that repeats its old action text does not hide a newer recorded step`() {
        val board =
            board(
                RunStarted(RUN, listOf(status(2)), at(0)),
                AgentUpdated(status(2, AgentState.WORKING, "announce", "do: create the announcement", at = at(1)), at(1)),
                StepRecorded(step(2, action = "click [12] \"Elan yarat\"", endedAt = T0.plusSeconds(2)), at(2)),
                AgentUpdated(status(2, AgentState.WORKING, "announce", "do: create the announcement", at = at(3)), at(3)),
            )

        board.view().card(2).lastAction shouldBe "click [12] \"Elan yarat\""
        board.view().card(2).lastActionAt shouldBe T0.plusSeconds(2)

        val finished = board.apply(AgentUpdated(status(2, AgentState.IDLE, "announce", "ok: create the announcement", at = at(4)), at(4)))

        finished.view().card(2).lastAction shouldBe "ok: create the announcement"
        finished.view().card(2).lastActionAt shouldBe T0.plusSeconds(4)
    }

    @Test
    fun `entering FAILED or BLOCKED is written to the timeline once with its reason`() {
        val failed =
            board(
                RunStarted(RUN, listOf(status(3), status(4)), at(0)),
                AgentUpdated(status(3, AgentState.FAILED, "register", "failed setup: verification_code_missing"), at(1)),
                AgentUpdated(status(3, AgentState.FAILED, "register", "failed setup: verification_code_missing"), at(2)),
                AgentUpdated(status(4, AgentState.BLOCKED, "tickets", "no_progress"), at(3)),
            )

        val timeline = failed.view().timeline

        timeline shouldHaveSize 2
        timeline[1].agentId shouldBe a(3)
        timeline[1].kind shouldBe TimelineKind.MESSAGE
        timeline[1].status shouldBe TimelineStatus.FAIL
        timeline[1].text shouldBe "sıradan çıxdı — failed setup: verification_code_missing"
        timeline[1].scenarioStep shouldBe "register"
        timeline[0].text shouldBe "bloklandı — no_progress"
        failed.view().card(3).lastFailureReason shouldBe "failed setup: verification_code_missing"
    }

    @Test
    fun `a started scenario step moves the header and is written to the timeline`() {
        val state = board(running, ScenarioStepStarted("announce", at(5)))

        val view = state.view()

        view.run.currentScenarioStep shouldBe "announce"
        view.timeline.single().text shouldBe "Addım başladı: announce"
        view.timeline.single().kind shouldBe TimelineKind.MESSAGE
        view.timeline.single().at shouldBe T0.plusSeconds(5)
    }

    @Test
    fun `a harness message is attributed to the agent it starts with`() {
        val state =
            board(
                running,
                Message("a07 failed setup step 'register' and is excluded", at(1)),
                Message("teardown\nfailed", at(2)),
            )

        val timeline = state.view().timeline

        timeline[1].agentId shouldBe a(7)
        timeline[0].agentId.shouldBeNull()
        timeline[0].text shouldBe "teardown failed"
    }

    @Test
    fun `an ended run freezes its elapsed time and shows the outcome and the report`() {
        val state = board(running, RunEnded(RUN, RunOutcome.FAILED, 90_000, "/tmp/evidence/run_1/report", at(90)))

        val view = state.snapshot(at(500))

        view.run.phase shouldBe RunPhase.FINISHED
        view.run.outcome shouldBe RunOutcome.FAILED
        view.run.elapsedMs shouldBe 90_000
        view.run.reportPath shouldBe "/tmp/evidence/run_1/report"
        state.reportPath shouldBe "/tmp/evidence/run_1/report"
        view.timeline.single().text shouldBe "Run bitdi: uğursuz"
        view.timeline.single().status shouldBe TimelineStatus.FAIL
        board(running, RunEnded(RUN, RunOutcome.PASSED, 1, null, at(1)))
            .view()
            .timeline
            .single()
            .status shouldBe TimelineStatus.OK
    }

    @Test
    fun `a run that never recorded its end is shown as interrupted`() {
        val view = board(running, RunEnded(RUN, null, 4_000, null, at(4))).view()

        view.run.phase shouldBe RunPhase.INTERRUPTED
        view.run.outcome.shouldBeNull()
        view.timeline.single().text shouldBe "Run yarımçıq qalıb"
    }

    @Test
    fun `the end of another run is ignored`() {
        val state = board(running)

        state.apply(RunEnded(OTHER_RUN, RunOutcome.PASSED, 1, null, at(1))) shouldBeSameInstanceAs state
    }

    @Test
    fun `a recorded step becomes the agent's last action and a timeline line`() {
        val record = step(2, action = "click [12] \"Elan yarat\"", scenarioStep = "announce", endedAt = T0.plusSeconds(5))

        val view = board(running, StepRecorded(record, at(5))).view()

        val card = view.card(2)
        card.lastAction shouldBe "click [12] \"Elan yarat\""
        card.lastActionAt shouldBe T0.plusSeconds(5)
        card.scenarioStep shouldBe "announce"
        card.actionsDone shouldBe 1
        card.failures shouldBe 0
        view.counters.stepsPassed shouldBe 1
        val entry = view.timeline.single()
        entry.kind shouldBe TimelineKind.STEP
        entry.status shouldBe TimelineStatus.OK
        entry.agentId shouldBe a(2)
        entry.at shouldBe T0.plusSeconds(5)
        entry.text shouldBe "click [12] \"Elan yarat\""
        entry.scenarioStep shouldBe "announce"
    }

    @Test
    fun `a failed step counts against its agent with the reason on one line`() {
        val record = step(2, action = "click [21] \"Dərc et\"", status = StepStatus.ERROR, detail = "timeout:\nno response in 30 s")

        val view = board(running, StepRecorded(record, at(1))).view()

        view.counters.stepsFailed shouldBe 1
        view.counters.stepsPassed shouldBe 0
        view.card(2).failures shouldBe 1
        view.card(2).actionsDone shouldBe 1
        view.card(2).lastFailureReason shouldBe "timeout: no response in 30 s"
        view.timeline.single().status shouldBe TimelineStatus.FAIL
        view.timeline.single().text shouldBe "click [21] \"Dərc et\" — timeout: no response in 30 s"
    }

    @Test
    fun `harness housekeeping that passed is neither a passed step nor an agent action`() {
        val view =
            board(
                running,
                StepRecorded(step(2, action = "open browser session", kind = StepKind.SYSTEM), at(1)),
                StepRecorded(step(3, action = "open browser session", kind = StepKind.SYSTEM, status = StepStatus.FAILED), at(2)),
            ).view()

        view.counters.stepsPassed shouldBe 0
        view.counters.stepsFailed shouldBe 1
        view.agents.map { it.agentId } shouldContainExactly listOf(a(3))
        view.card(3).actionsDone shouldBe 0
        view.card(3).failures shouldBe 1
        view.card(3).scenarioStep.shouldBeNull()
        view.timeline.map { it.status } shouldContainExactly listOf(TimelineStatus.FAIL, TimelineStatus.INFO)
        view.timeline.map { it.scenarioStep } shouldContainExactly listOf(null, null)
    }

    @Test
    fun `a skipped step is shown but not counted as an action`() {
        val view = board(running, StepRecorded(step(2, status = StepStatus.SKIPPED), at(1))).view()

        view.card(2).actionsDone shouldBe 0
        view.counters.stepsPassed shouldBe 0
        view.counters.stepsFailed shouldBe 0
        view.timeline.single().status shouldBe TimelineStatus.INFO
    }

    @Test
    fun `the reason the LLM gave for a do step is shown as a dialog line before the step`() {
        val view =
            board(
                running,
                StepRecorded(step(2, kind = StepKind.DO, reason = "Yeni elan formunu açmaq lazımdır"), at(1)),
                StepRecorded(step(2, kind = StepKind.RUN, action = "run register_and_login", reason = "ignored"), at(2)),
            ).view()

        view.timeline.map { it.kind } shouldContainExactly listOf(TimelineKind.STEP, TimelineKind.STEP, TimelineKind.DIALOG)
        view.timeline[2].text shouldBe "Yeni elan formunu açmaq lazımdır"
        view.timeline[2].status shouldBe TimelineStatus.INFO
        view.timeline[2].seq shouldBe view.timeline[1].seq - 1
    }

    @Test
    fun `a screenshot becomes the agent's latest picture and other artifacts leave the board unchanged`() {
        val first = artifact("a02", file = "0001-screenshot.png")
        val second = artifact("a02", file = "0002-screenshot.png")
        val state = board(running, ArtifactRecorded(first, at(1)), ArtifactRecorded(second, at(2)))

        state.view().card(2).lastScreenshotArtifactId shouldBe second.artifactId
        state.apply(ArtifactRecorded(artifact("a02", ArtifactType.A11Y), at(3))) shouldBeSameInstanceAs state
        state.apply(ArtifactRecorded(artifact("harness"), at(3))) shouldBeSameInstanceAs state
    }

    @Test
    fun `events receipts assertions and findings feed the counters`() {
        val announced = event(1, name = "announcement_created", objectId = "184")
        val view =
            board(
                running,
                EventRecorded(announced, at(1)),
                ReceiptRecorded(receipt(announced, 2, received = true, latencyMs = 830), at(2)),
                ReceiptRecorded(receipt(announced, 3, received = false), at(3)),
                AssertionRecorded(assertion(2, Verdict.PASSED), at(4)),
                AssertionRecorded(assertion(3, Verdict.FAILED, observed = "görünmədi"), at(5)),
                AssertionRecorded(assertion(4, Verdict.SKIPPED, note = "agent excluded"), at(6)),
                FindingRecorded(finding(3), at(7)),
            ).view()

        with(view.counters) {
            events shouldBe 1
            receiptsReceived shouldBe 1
            receiptsMissing shouldBe 1
            assertionsPassed shouldBe 1
            assertionsFailed shouldBe 1
            assertionsSkipped shouldBe 1
            findings shouldBe 1
        }
        view.timeline.map { it.kind } shouldContainExactly
            listOf(
                TimelineKind.FINDING,
                TimelineKind.ASSERTION,
                TimelineKind.ASSERTION,
                TimelineKind.ASSERTION,
                TimelineKind.RECEIPT,
                TimelineKind.RECEIPT,
                TimelineKind.EVENT,
            )
        view.timeline.last().text shouldBe "announcement_created · obyekt 184"
        view.timeline.last().agentId shouldBe a(1)
    }

    @Test
    fun `a receipt names its event and a missing one is a failure line`() {
        val announced = event(1, name = "announcement_created")
        val view =
            board(
                running,
                EventRecorded(announced, at(1)),
                ReceiptRecorded(receipt(announced, 2, latencyMs = 830), at(2)),
                ReceiptRecorded(receipt(announced, 3, received = false), at(12)),
            ).view()

        view.timeline[1].text shouldBe "announcement_created · 830 ms"
        view.timeline[1].status shouldBe TimelineStatus.OK
        view.timeline[1].at shouldBe announced.t0.plusMillis(830)
        view.timeline[0].text shouldBe "announcement_created · görünmədi"
        view.timeline[0].status shouldBe TimelineStatus.FAIL
        view.timeline[0].agentId shouldBe a(3)
        view.timeline[0].at shouldBe T0.plusSeconds(12)
    }

    @Test
    fun `a failed assertion is a failure of its agent and shows what was observed`() {
        val view =
            board(
                running,
                AssertionRecorded(assertion(3, Verdict.FAILED, expected = "'Yeni elan' görünür", observed = "görünmədi"), at(1)),
                AssertionRecorded(assertion(4, Verdict.PASSED), at(2)),
            ).view()

        view.card(3).failures shouldBe 1
        view.card(3).lastFailureReason shouldBe "visible_text: 'Yeni elan' görünür → görünmədi"
        view.agents.map { it.agentId } shouldContainExactly listOf(a(3))
        view.timeline[1].status shouldBe TimelineStatus.FAIL
        view.timeline[1].scenarioStep shouldBe "receive"
    }

    @Test
    fun `findings are listed newest first and notices are not failure lines`() {
        val backend = finding(2, FindingClass.BACKEND, note = "oracle has no announcement")
        val investigate = finding(null, FindingClass.INVESTIGATE, note = "slow delivery")

        val view = board(running, FindingRecorded(backend, at(1)), FindingRecorded(investigate, at(2))).view()

        view.findings.map { it.findingId } shouldContainExactly listOf(investigate.findingId, backend.findingId)
        view.findings[1].findingClass shouldBe FindingClass.BACKEND
        view.findings[1].a shouldBe "a02 elanı yaratdı"
        view.findings[1].scenarioStep shouldBe "receive"
        view.timeline.map { it.status } shouldContainExactly listOf(TimelineStatus.INFO, TimelineStatus.FAIL)
        view.timeline[1].text shouldBe "BACKEND: oracle has no announcement"
    }

    @Test
    fun `evidence of another run is ignored while the shown run goes on and adopted once it ended`() {
        val state = board(running)

        state.apply(StepRecorded(step(2, runId = OTHER_RUN), at(1))) shouldBeSameInstanceAs state
        state.apply(AgentsPlanned(OTHER_RUN, listOf(profile(2)), at(1))) shouldBeSameInstanceAs state

        val ended = state.apply(RunEnded(RUN, RunOutcome.PASSED, 10, null, at(2)))
        val adopted = ended.apply(StepRecorded(step(2, runId = OTHER_RUN), at(3))).view()

        adopted.run.runId shouldBe OTHER_RUN
        adopted.run.phase shouldBe RunPhase.RUNNING
        adopted.counters.stepsPassed shouldBe 1
    }

    @Test
    fun `evidence adopts its run when no run is shown yet`() {
        val view = board(StepRecorded(step(2), at(3))).snapshot(at(10))

        view.run.runId shouldBe RUN
        view.run.campaignName.shouldBeNull()
        view.run.elapsedMs shouldBe 7_000
        view.card(2).actionsDone shouldBe 1
    }

    @Test
    fun `the timeline keeps the newest entries and each agent its own newest thirty`() {
        val updates = (1..(DashboardState.TIMELINE_LIMIT + 20)).map { StepRecorded(step(2 + it % 2, action = "step $it"), at(it.toLong())) }

        val state = board(running, *updates.toTypedArray())
        val view = state.view()

        view.timeline shouldHaveSize DashboardState.TIMELINE_LIMIT
        view.timeline.first().text shouldBe "step ${DashboardState.TIMELINE_LIMIT + 20}"
        view.timeline.zipWithNext().all { (newer, older) -> newer.seq > older.seq } shouldBe true
        val own = state.agentDetail(a(2)).shouldNotBeNull().timeline
        own shouldHaveSize DashboardState.AGENT_TIMELINE_LIMIT
        own.all { it.agentId == a(2) } shouldBe true
        own.first().text shouldBe "step ${DashboardState.TIMELINE_LIMIT + 20}"
    }

    @Test
    fun `the findings list keeps the newest ones but the counter counts all`() {
        val updates = (1..(DashboardState.FINDINGS_LIMIT + 5)).map { FindingRecorded(finding(2, note = "finding $it"), at(1)) }

        val view = board(running, *updates.toTypedArray()).view()

        view.findings shouldHaveSize DashboardState.FINDINGS_LIMIT
        view.findings.first().note shouldBe "finding ${DashboardState.FINDINGS_LIMIT + 5}"
        view.counters.findings shouldBe DashboardState.FINDINGS_LIMIT + 5
    }

    @Test
    fun `long texts become one clipped line`() {
        val action = "type [9] \"" + "x".repeat(400) + "\"\nsecond line"

        val view = board(running, StepRecorded(step(2, action = action), at(1))).view()

        view.card(2).lastAction!!.length shouldBe TimelineTexts.ACTION_CHARS
        view.card(2).lastAction!! shouldEndWith "…"
        view.timeline
            .single()
            .text.length shouldBe TimelineTexts.TEXT_CHARS
        view.timeline.single().text shouldNotContain "\n"
    }

    @Test
    fun `agent detail gives the card with its own timeline and nothing for an unknown agent`() {
        val state =
            board(
                running,
                StepRecorded(step(2, action = "a02 action"), at(1)),
                StepRecorded(step(3, action = "a03 action"), at(2)),
            )

        val detail = state.agentDetail(a(2)).shouldNotBeNull()

        detail.card.agentId shouldBe a(2)
        detail.timeline.map { it.text } shouldContainExactly listOf("a02 action")
        state.agentDetail(a(9)).shouldBeNull()
    }

    @Test
    fun `applying an update leaves the previous state untouched`() {
        val before = board(running, StepRecorded(step(2), at(1)))
        val snapshotBefore = before.view()

        val after = before.apply(StepRecorded(step(2, status = StepStatus.FAILED), at(2)))

        before.view() shouldBe snapshotBefore
        after.version shouldBe before.version + 1
        after.view().counters.stepsFailed shouldBe 1
    }

    @Test
    fun `a replayed state can take over a live version`() {
        val replayed = board(running)

        replayed.withVersionAtLeast(100).version shouldBe 100
        replayed.withVersionAtLeast(0) shouldBeSameInstanceAs replayed
    }
}
