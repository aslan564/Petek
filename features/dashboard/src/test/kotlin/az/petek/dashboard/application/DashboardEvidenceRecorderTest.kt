package az.petek.dashboard.application

import az.petek.core.testing.FakeHarnessClock
import az.petek.dashboard.domain.TimelineKind
import az.petek.dashboard.testing.FailingClock
import az.petek.dashboard.testing.Records.RUN
import az.petek.dashboard.testing.Records.a
import az.petek.dashboard.testing.Records.artifact
import az.petek.dashboard.testing.Records.assertion
import az.petek.dashboard.testing.Records.event
import az.petek.dashboard.testing.Records.finding
import az.petek.dashboard.testing.Records.receipt
import az.petek.dashboard.testing.Records.status
import az.petek.dashboard.testing.Records.step
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException

class DashboardEvidenceRecorderTest {
    private val announced = event(1)
    private val usage = UsageRecord(RUN, a(2), 1_200, 300, 0, 0.01, 2)

    private suspend fun EvidenceRecorder.recordOneOfEach() {
        step(step(2, action = "click [12] \"Elan yarat\""))
        artifact(artifact("a02"))
        event(announced)
        receipt(receipt(announced, 3))
        assertion(assertion(3, Verdict.FAILED))
        finding(finding(3))
        usage(usage)
    }

    @Test
    fun `every record reaches the delegate unchanged and the dashboard shows it`() =
        runBlocking<Unit> {
            val store = InMemoryEvidence()
            val dashboard = LiveDashboard(FakeHarnessClock())
            dashboard.runStarted(RUN, listOf(status(1), status(2), status(3)))

            DashboardEvidenceRecorder(store, dashboard).recordOneOfEach()

            store.stepList shouldHaveSize 1
            store.artifactList shouldHaveSize 1
            store.eventList shouldContainExactly listOf(announced)
            store.receiptList shouldHaveSize 1
            store.assertionList shouldHaveSize 1
            store.findingList shouldHaveSize 1
            store.usageList shouldContainExactly listOf(usage)
            val view = dashboard.snapshot()
            view.agents[1].lastAction shouldBe "click [12] \"Elan yarat\""
            view.agents[1].lastScreenshotArtifactId shouldBe store.artifactList.single().artifactId
            view.counters.events shouldBe 1
            view.counters.receiptsReceived shouldBe 1
            view.counters.assertionsFailed shouldBe 1
            view.counters.findings shouldBe 1
            view.timeline.map { it.kind } shouldContainExactly
                listOf(TimelineKind.FINDING, TimelineKind.ASSERTION, TimelineKind.RECEIPT, TimelineKind.EVENT, TimelineKind.STEP)
        }

    @Test
    fun `a broken dashboard never breaks recording`() =
        runBlocking<Unit> {
            val store = InMemoryEvidence()
            val clock = FailingClock(FakeHarnessClock())
            val dashboard = LiveDashboard(clock)
            clock.failing = true

            DashboardEvidenceRecorder(store, dashboard).recordOneOfEach()

            store.stepList shouldHaveSize 1
            store.artifactList shouldHaveSize 1
            store.eventList shouldHaveSize 1
            store.receiptList shouldHaveSize 1
            store.assertionList shouldHaveSize 1
            store.findingList shouldHaveSize 1
            store.usageList shouldHaveSize 1
            clock.failing = false
            dashboard.snapshot().timeline shouldHaveSize 0
        }

    @Test
    fun `a record the delegate refuses fails for the caller and never reaches the dashboard`() =
        runBlocking<Unit> {
            val dashboard = LiveDashboard(FakeHarnessClock())
            val recorder = DashboardEvidenceRecorder(RefusingRecorder(), dashboard)

            shouldThrow<IOException> { recorder.step(step(2)) }
            shouldThrow<IOException> { recorder.artifact(artifact("a02")) }
            shouldThrow<IOException> { recorder.event(announced) }
            shouldThrow<IOException> { recorder.receipt(receipt(announced, 3)) }
            shouldThrow<IOException> { recorder.assertion(assertion(3)) }
            shouldThrow<IOException> { recorder.finding(finding(3)) }
            shouldThrow<IOException> { recorder.usage(usage) }

            dashboard.snapshot().version shouldBe 0
            dashboard.snapshot().run.runId shouldBe null
        }

    /** An evidence store whose disk is full. */
    private class RefusingRecorder : EvidenceRecorder {
        override suspend fun step(record: StepRecord) = refuse()

        override suspend fun artifact(record: ArtifactRecord) = refuse()

        override suspend fun event(record: EventRecord) = refuse()

        override suspend fun receipt(record: EventReceipt) = refuse()

        override suspend fun assertion(record: AssertionRecord) = refuse()

        override suspend fun finding(record: FindingRecord) = refuse()

        override suspend fun usage(record: UsageRecord) = refuse()

        private fun refuse(): Nothing = throw IOException("disk full")
    }
}
