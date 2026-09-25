package az.petek.reporting.application

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.EvidenceSource.ORACLE
import az.petek.evidence.domain.EvidenceSource.RECEIVER
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict.FAILED
import az.petek.evidence.domain.Verdict.PASSED
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.reporting.ReportTestData.RUN_ID
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.finding
import az.petek.reporting.ReportTestData.run
import az.petek.reporting.ReportTestData.step
import az.petek.reporting.domain.Judge
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportWriter
import az.petek.reporting.domain.RunNotFoundException
import az.petek.reporting.domain.ThreeSourceJudge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FinalizeRunUseCaseTest {
    @TempDir
    lateinit var root: Path

    private val evidence = InMemoryEvidence()
    private val store by lazy { InMemoryArtifactStore(root) }
    private val judge = CountingJudge(ThreeSourceJudge(SequentialIdGenerator()))
    private val markdown = RecordingWriter("report.md")
    private val html = RecordingWriter("index.html")

    private fun TestScope.useCase(recorder: EvidenceRecorder = evidence) =
        FinalizeRunUseCase(
            runs = evidence,
            query = evidence,
            recorder = recorder,
            artifacts = store,
            judge = judge,
            builder = BuildReportUseCase(evidence, evidence, store),
            writers = listOf(markdown, html),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    /** A receiver that did not see an announcement the oracle confirms, and a registration without e-mail. */
    private suspend fun seedFailingRun() {
        evidence.create(run())
        evidence.assertion(assertion("read_announce", "a02", RECEIVER, FAILED, artifacts = listOf("art_shot")))
        evidence.assertion(assertion("read_announce", "a02", ORACLE, PASSED))
        evidence.step(step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "mail_timeout: none", stepId = "stp_join_a07"))
        store.write(RUN_ID, StepId("stp_join_a07"), "a07", ArtifactType.SCREENSHOT, byteArrayOf(1)).also { evidence.artifact(it) }
        store.write(RUN_ID, StepId("stp_join_a07"), "a07", ArtifactType.MAIL, byteArrayOf(2)).also { evidence.artifact(it) }
    }

    @Test
    fun `finalize judges the run records the findings and writes every format into the report directory`() =
        runTest {
            seedFailingRun()

            val directory = useCase().finalize(RUN_ID)

            directory shouldBe root.resolve("run_test").resolve("report")
            directory.resolve("report.md").shouldExist()
            directory.resolve("index.html").shouldExist()
            evidence.findingList.map { it.findingClass } shouldContainExactly listOf(FindingClass.DELIVERY_UI, FindingClass.BACKEND)
            markdown.models.single().findings shouldContainExactly evidence.findingList
            html.models.single().findings shouldContainExactly evidence.findingList
        }

    @Test
    fun `a finding from a failed action is linked to the evidence of its step`() =
        runTest {
            seedFailingRun()

            useCase().finalize(RUN_ID)

            val (assertionFinding, stepFinding) = evidence.findingList
            assertionFinding.artifactIds shouldContainExactly listOf(ArtifactId("art_shot"))
            stepFinding.artifactIds shouldContainExactly evidence.artifactList.map { it.artifactId }
        }

    @Test
    fun `finalizing twice judges once and rewrites the report`() =
        runTest {
            seedFailingRun()
            val useCase = useCase()

            useCase.finalize(RUN_ID)
            val before = evidence.findingList.toList()
            useCase.finalize(RUN_ID)

            judge.calls.get() shouldBe 1
            evidence.findingList shouldContainExactly before
            markdown.models shouldHaveSize 2
            Files.readString(root.resolve("run_test/report/report.md")) shouldContain "findings=2"
        }

    @Test
    fun `a run that already has findings is not judged again`() =
        runTest {
            seedFailingRun()
            val recorded = finding("fnd_old", "read_announce", "a02")
            evidence.finding(recorded)

            useCase().finalize(RUN_ID)

            judge.calls.get() shouldBe 0
            evidence.findingList shouldContainExactly listOf(recorded)
        }

    @Test
    fun `concurrent finalizations of one run record its findings once`() =
        runTest {
            seedFailingRun()
            val useCase = useCase(recorder = YieldingRecorder(evidence))

            val directories = (1..3).map { async { useCase.finalize(RUN_ID) } }.awaitAll()

            directories.toSet() shouldHaveSize 1
            evidence.findingList shouldHaveSize 2
            judge.calls.get() shouldBe 1
        }

    @Test
    fun `a run still running is reported with provisional findings that are not recorded`() =
        runTest {
            evidence.create(run(endedAt = null, result = RunResult.RUNNING))
            evidence.assertion(assertion("read_announce", "a02", RECEIVER, FAILED))
            evidence.assertion(assertion("read_announce", "a02", ORACLE, PASSED))
            val useCase = useCase()

            useCase.finalize(RUN_ID)

            evidence.findingList.shouldBeEmpty()
            markdown.models
                .single()
                .findings
                .map { it.findingClass } shouldContainExactly listOf(FindingClass.DELIVERY_UI)

            // Evidence that arrives later still counts once the run has finished.
            evidence.step(step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "mail_timeout: none", stepId = "stp_join_a07"))
            evidence.finish(RUN_ID, RunResult.FAILED, run().endedAt!!)
            useCase.finalize(RUN_ID)

            evidence.findingList.map { it.findingClass } shouldContainExactly listOf(FindingClass.DELIVERY_UI, FindingClass.BACKEND)
            markdown.models.last().findings shouldContainExactly evidence.findingList
        }

    @Test
    fun `a run that passed gets a report without findings`() =
        runTest {
            evidence.create(run())
            evidence.assertion(assertion("announce", "a01", ORACLE, PASSED))

            useCase().finalize(RUN_ID)

            evidence.findingList.shouldBeEmpty()
            markdown.models
                .single()
                .findings
                .shouldBeEmpty()
        }

    @Test
    fun `an unknown run is rejected before anything is written`() =
        runTest {
            shouldThrow<RunNotFoundException> { useCase().finalize(RunId("run_missing")) }

            markdown.models.shouldBeEmpty()
            judge.calls.get() shouldBe 0
        }

    @Test
    fun `a failing writer does not cost the other formats and its failure is reported`() =
        runTest {
            seedFailingRun()
            val broken = FailingWriter("broken.html")
            val useCase =
                FinalizeRunUseCase(
                    runs = evidence,
                    query = evidence,
                    recorder = evidence,
                    artifacts = store,
                    judge = judge,
                    builder = BuildReportUseCase(evidence, evidence, store),
                    writers = listOf(broken, markdown, html),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val error = shouldThrow<IllegalStateException> { useCase.finalize(RUN_ID) }

            error.message shouldBe "disk full"
            markdown.models shouldHaveSize 1
            html.models shouldHaveSize 1
            root.resolve("run_test/report/report.md").shouldExist()
            evidence.findingList shouldHaveSize 2
        }

    @Test
    fun `writers must write distinct files`() {
        shouldThrow<IllegalArgumentException> {
            FinalizeRunUseCase(
                evidence,
                evidence,
                evidence,
                store,
                judge,
                BuildReportUseCase(evidence, evidence, store),
                listOf(RecordingWriter("report.md"), RecordingWriter("report.md")),
            )
        }
    }

    private class RecordingWriter(
        override val fileName: String,
    ) : ReportWriter {
        val models = CopyOnWriteArrayList<ReportModel>()

        override fun write(
            model: ReportModel,
            directory: Path,
        ): Path {
            models += model
            return Files.writeString(directory.resolve(fileName), "findings=${model.findings.size}")
        }
    }

    private class FailingWriter(
        override val fileName: String,
    ) : ReportWriter {
        override fun write(
            model: ReportModel,
            directory: Path,
        ): Path = error("disk full")
    }

    private class CountingJudge(
        private val delegate: Judge,
    ) : Judge by delegate {
        val calls = AtomicInteger()

        override fun findings(
            run: RunRecord,
            assertions: List<AssertionRecord>,
            steps: List<StepRecord>,
        ): List<FindingRecord> {
            calls.incrementAndGet()
            return delegate.findings(run, assertions, steps)
        }
    }

    /** Suspends before every write, so an unserialized finalize would interleave with another one. */
    private class YieldingRecorder(
        private val delegate: InMemoryEvidence,
    ) : EvidenceRecorder by delegate {
        override suspend fun finding(record: FindingRecord) {
            yield()
            delegate.finding(record)
        }
    }
}
