package az.petek.reporting.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceSource.HARNESS
import az.petek.evidence.domain.EvidenceSource.ORACLE
import az.petek.evidence.domain.EvidenceSource.RECEIVER
import az.petek.evidence.domain.EvidenceSource.SENDER
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict.FAILED
import az.petek.evidence.domain.Verdict.PASSED
import az.petek.evidence.domain.Verdict.SKIPPED
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.reporting.ReportTestData.RUN_ID
import az.petek.reporting.ReportTestData.START
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.event
import az.petek.reporting.ReportTestData.finding
import az.petek.reporting.ReportTestData.receipt
import az.petek.reporting.ReportTestData.run
import az.petek.reporting.ReportTestData.step
import az.petek.reporting.ReportTestData.usage
import az.petek.reporting.domain.AgentDirectory
import az.petek.reporting.domain.FailedAgentRow
import az.petek.reporting.domain.RunNotFoundException
import az.petek.reporting.domain.StabilityRow
import az.petek.reporting.domain.StepRow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BuildReportUseCaseTest {
    private val evidence = InMemoryEvidence()
    private val store = InMemoryArtifactStore(Path.of("/evidence"))
    private val names = AgentDirectory { mapOf(AgentId("a01") to "Əli Məmmədov", AgentId("a07") to "Vəli Həsənov") }
    private val useCase = BuildReportUseCase(evidence, evidence, store, names)

    private suspend fun artifact(
        stepId: String,
        owner: String,
        type: ArtifactType = ArtifactType.SCREENSHOT,
        runId: RunId = RUN_ID,
    ): ArtifactRecord = store.write(runId, StepId(stepId), owner, type, byteArrayOf(1, 2, 3)).also { evidence.artifact(it) }

    /** A small but complete run: one sender, receivers, a registration failure, a blocked agent and usage. */
    private suspend fun seedRun() {
        evidence.create(run())
        listOf(
            step("announce", "a01", StepStatus.PASSED, StepKind.DO, stepId = "stp_announce_a01", durationMs = 4_200),
            step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "mail_timeout: no e-mail in 60s", stepId = "stp_join_a07"),
            step("read_announce", "a02", StepStatus.PASSED, StepKind.WAIT, stepId = "stp_wait_a02"),
            step("read_announce", "a03", StepStatus.BLOCKED, StepKind.DO, detail = "no progress for 120s", stepId = "stp_read_a03"),
            step("read_announce", null, kind = StepKind.SYSTEM, action = "network_observation", detail = "SSE, polling", stepId = "s1"),
            step("ticket", null, kind = StepKind.SYSTEM, action = "network_observation", detail = "POLLING,WEBSOCKET,", stepId = "s2"),
            step("ticket", null, kind = StepKind.SYSTEM, action = "teardown", detail = "IGNORED", stepId = "s3"),
            step("read_announce", "a02", StepStatus.FAILED, StepKind.ASSERT, detail = "timeout", stepId = "stp_assert_a02"),
            step("race", "a05", StepStatus.SKIPPED, StepKind.DO, stepId = "stp_race_a05"),
        ).forEach { evidence.step(it) }
        listOf(
            assertion("announce", "a01", SENDER, PASSED),
            assertion("announce", "a01", ORACLE, PASSED),
            assertion("read_announce", "a02", RECEIVER, FAILED),
            assertion("read_announce", "a02", ORACLE, PASSED),
            assertion("read_announce", "a04", HARNESS, FAILED),
            assertion("read_announce", "a04", ORACLE, SKIPPED),
        ).forEach { evidence.assertion(it) }
        evidence.event(event("evt_1"))
        evidence.receipt(receipt("evt_1", "a02", 700))
        evidence.receipt(receipt("evt_1", "a03", null, received = false))
        evidence.usage(usage("a01", input = 1_000_000, output = 234_567, cost = 0.03))
        evidence.usage(usage("a02", input = 234_567, output = 0, cost = null))
        evidence.finding(finding("fnd_1", "read_announce", "a02"))
    }

    @Test
    fun `the summary counts steps assertions agents duration tokens and cost`() =
        runTest {
            seedRun()

            val summary = useCase.build(RUN_ID).summary

            summary.stepsPassed shouldBe 2
            summary.stepsFailed shouldBe 2
            summary.assertionsPassed shouldBe 3
            summary.assertionsFailed shouldBe 2
            summary.assertionsSkipped shouldBe 1
            summary.agents shouldBe 6
            summary.durationMs shouldBe 245_000
            summary.inputTokens shouldBe 1_234_567
            summary.outputTokens shouldBe 234_567
            summary.costUsd shouldBe 0.03
        }

    @Test
    fun `real-time transports come from network observations without duplicates`() =
        runTest {
            seedRun()

            useCase.build(RUN_ID).summary.realtimeTransports shouldContainExactly listOf("SSE", "POLLING", "WEBSOCKET")
        }

    @Test
    fun `the step table lists do run and wait steps with their last screenshot`() =
        runTest {
            seedRun()
            artifact("stp_announce_a01", "a01")
            val last = artifact("stp_announce_a01", "a01")
            artifact("stp_announce_a01", "a01", ArtifactType.DOM)
            val join = artifact("stp_join_a07", "a07")

            val steps = useCase.build(RUN_ID).steps

            steps.map { it.scenarioStep to it.agentId } shouldContainExactly
                listOf("announce" to "a01", "join" to "a07", "read_announce" to "a02", "read_announce" to "a03", "race" to "a05")
            steps.first() shouldBe
                StepRow(
                    scenarioStep = "announce",
                    agentId = "a01",
                    agentName = "Əli Məmmədov",
                    kind = "DO",
                    status = "PASSED",
                    durationMs = 4_200,
                    detail = null,
                    screenshot = last.artifactId.value,
                )
            steps[1].screenshot shouldBe join.artifactId.value
            steps[2].screenshot.shouldBeNull()
            steps[2].agentName.shouldBeNull()
        }

    @Test
    fun `artifact links are relative to the report directory`() =
        runTest {
            seedRun()
            val shot = artifact("stp_announce_a01", "a01")
            val mail = artifact("stp_join_a07", "a07", ArtifactType.MAIL)

            val links = useCase.build(RUN_ID).artifactLinks

            links shouldContainExactly
                mapOf(
                    shot.artifactId.value to "../a01/0001-screenshot.png",
                    mail.artifactId.value to "../a07/0002-mail.json",
                )
        }

    @Test
    fun `links fall back to the store layout when paths cannot be related`() =
        runTest {
            val odd = RelativeResolvingStore(store)
            val record = odd.write(RUN_ID, StepId("stp_1"), "a01", ArtifactType.SCREENSHOT, byteArrayOf(1))
            evidence.create(run())
            evidence.artifact(record)

            val links = BuildReportUseCase(evidence, evidence, odd).build(RUN_ID).artifactLinks

            links shouldContainExactly mapOf(record.artifactId.value to "../a01/0001-screenshot.png")
        }

    @Test
    fun `failed agents are grouped per agent and step with the failure reason and name`() =
        runTest {
            seedRun()
            evidence.step(step("join", "a07", StepStatus.ERROR, StepKind.RUN, detail = "otp_rejected", stepId = "stp_join_a07_2"))
            evidence.step(step("ticket", "a03", StepStatus.FAILED, StepKind.DO, detail = "Button\n not found", stepId = "stp_ticket_a03"))

            useCase.build(RUN_ID).failedAgents shouldContainExactly
                listOf(
                    FailedAgentRow("a03", "a03", "read_announce", "blocked"),
                    FailedAgentRow("a03", "a03", "ticket", "Button not found"),
                    FailedAgentRow("a07", "Vəli Həsənov", "join", "mail_timeout, otp_rejected"),
                )
        }

    @Test
    fun `a failure without key or detail is explained by its status`() =
        runTest {
            evidence.create(run())
            evidence.step(step("announce", "a01", StepStatus.ERROR, detail = null))

            useCase
                .build(RUN_ID)
                .failedAgents
                .single()
                .reason shouldBe "error"
        }

    @Test
    fun `latency findings and assertions are taken from the evidence store`() =
        runTest {
            seedRun()

            val model = useCase.build(RUN_ID)

            model.latency.single().let {
                it.event shouldBe "announcement_created #42"
                it.received shouldBe 1
                it.missing shouldContainExactly listOf("a03")
                it.avgMs shouldBe 700
            }
            model.findings shouldContainExactly evidence.findingList
            model.assertions shouldContainExactly evidence.assertionList
            model.usage shouldContainExactly evidence.usageList
            model.run shouldBe run()
        }

    @Test
    fun `a run outside a repeat group has no stability table`() =
        runTest {
            seedRun()

            useCase.build(RUN_ID).stability.shouldBeNull()
        }

    @Test
    fun `a run of a repeat group gets stability across all runs of the group`() =
        runTest {
            val ids = (1..3).map { RunId("run_r$it") }
            // Created out of order: the report must follow repeat order.
            listOf(2, 0, 1).forEach { i -> evidence.create(run(runId = ids[i], repeatGroup = "grp", repeatIndex = i + 1)) }
            evidence.create(run(runId = RunId("run_elsewhere"), repeatGroup = "other", repeatIndex = 1))
            ids.forEachIndexed { i, id ->
                evidence.step(step("announce", "a01", runId = id, stepId = "a_$i"))
                val status = if (i == 1) StepStatus.FAILED else StepStatus.PASSED
                evidence.step(step("read_announce", "a02", status, runId = id, stepId = "r_$i"))
            }
            evidence.step(step("join", "a09", runId = ids[2], stepId = "j_2"))

            val stability = useCase.build(ids[1]).stability.shouldNotBeNull()

            stability shouldContainExactly
                listOf(
                    StabilityRow("announce", 3, 3),
                    StabilityRow("read_announce", 3, 2),
                    StabilityRow("join", 3, 1),
                )
        }

    @Test
    fun `an open run is measured up to its last recorded step`() =
        runTest {
            evidence.create(run(endedAt = null))
            evidence.step(step("announce", "a01", startOffsetMs = 1_000, durationMs = 2_000, stepId = "s1"))
            evidence.step(step("read_announce", "a02", startOffsetMs = 10_000, durationMs = 5_000, stepId = "s2"))

            useCase.build(RUN_ID).summary.durationMs shouldBe 15_000
        }

    @Test
    fun `an empty run has zero duration no cost and empty tables`() =
        runTest {
            evidence.create(run(endedAt = null))

            val model = useCase.build(RUN_ID)

            model.summary.durationMs shouldBe 0
            model.summary.costUsd.shouldBeNull()
            model.summary.agents shouldBe 0
            model.steps shouldBe emptyList()
            model.latency shouldBe emptyList()
            model.failedAgents shouldBe emptyList()
            model.artifactLinks shouldBe emptyMap()
        }

    @Test
    fun `cost is unknown when no usage record reports one`() =
        runTest {
            evidence.create(run())
            evidence.usage(usage("a01", 10, 5, cost = null))

            useCase
                .build(RUN_ID)
                .summary.costUsd
                .shouldBeNull()
        }

    @Test
    fun `evidence of other runs stays out of the report`() =
        runTest {
            seedRun()
            val other = RunId("run_other")
            evidence.create(run(runId = other, startedAt = START.plusSeconds(1)))
            evidence.step(step("announce", "a09", StepStatus.FAILED, detail = "login_failed", runId = other, stepId = "o1"))
            evidence.usage(usage("a09", 5, 5, 1.0, runId = other))

            val model = useCase.build(RUN_ID)

            model.steps.none { it.agentId == "a09" } shouldBe true
            model.summary.costUsd shouldBe 0.03
        }

    @Test
    fun `an unknown run is rejected`() =
        runTest {
            val error = shouldThrow<RunNotFoundException> { useCase.build(RunId("run_missing")) }

            error.runId shouldBe RunId("run_missing")
        }

    /** A store whose artifact paths are relative while its run directory is absolute, so they cannot be related. */
    private class RelativeResolvingStore(
        private val delegate: ArtifactStore,
    ) : ArtifactStore by delegate {
        override fun resolve(record: ArtifactRecord): Path = Path.of(record.relativePath)

        override fun runDirectory(runId: RunId): Path = Path.of("/elsewhere", runId.value)
    }
}
