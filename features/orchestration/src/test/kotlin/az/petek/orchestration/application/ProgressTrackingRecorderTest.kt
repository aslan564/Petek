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

package az.petek.orchestration.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class ProgressTrackingRecorderTest {
    private val runId = RunId("run_1")
    private val a07 = AgentId("a07")
    private val now = Instant.parse("2026-01-01T10:00:00Z")
    private val delegate = InMemoryEvidence()
    private val progress = CopyOnWriteArrayList<AgentId>()
    private val recorder = ProgressTrackingRecorder(delegate, progress::add)

    private fun step(agentId: AgentId?) =
        StepRecord(
            StepId("stp_1"),
            runId,
            agentId,
            "announce",
            StepKind.DO,
            "click [3]",
            "because",
            now,
            now,
            0,
            StepStatus.PASSED,
            null,
            CorrelationId("cor_1"),
        )

    private fun assertion(agentId: AgentId?) =
        AssertionRecord(
            StepId("stp_1"),
            runId,
            agentId,
            "announce",
            "visible_text",
            EvidenceSource.RECEIVER,
            "x",
            "x",
            Verdict.PASSED,
            12,
            null,
            emptyList(),
        )

    private fun artifact(path: String) =
        ArtifactRecord(ArtifactId("art_1"), runId, StepId("stp_1"), ArtifactType.SCREENSHOT, path, "sha", 3)

    @Test
    fun `steps and assertions of an agent count as its progress`() =
        runTest {
            recorder.step(step(a07))
            recorder.assertion(assertion(a07))

            progress shouldContainExactly listOf(a07, a07)
            delegate.stepList shouldHaveSize 1
            delegate.assertionList shouldHaveSize 1
        }

    @Test
    fun `an artifact counts for the agent named in its path`() =
        runTest {
            recorder.artifact(artifact("run_1/a07/0003-screenshot.png"))
            recorder.artifact(artifact("run_1\\a120\\0001-screenshot.png"))
            recorder.artifact(artifact("run_1/a1000/0002-a11y.yaml"))
            recorder.artifact(artifact("run_1/a25000/0002-a11y.yaml"))

            progress shouldContainExactly listOf(a07, AgentId("a120"), AgentId("a1000"), AgentId("a25000"))
            delegate.artifactList shouldHaveSize 4
        }

    @Test
    fun `path segments that only look like agent ids are not taken for one`() =
        runTest {
            recorder.artifact(artifact("run_1/a007/0001-screenshot.png"))
            recorder.artifact(artifact("run_1/a00/0001-screenshot.png"))

            progress.shouldBeEmpty()
        }

    @Test
    fun `harness records without an agent are passed through without progress`() =
        runTest {
            recorder.step(step(null))
            recorder.assertion(assertion(null))
            recorder.artifact(artifact("run_1/harness/0001-log.txt"))

            progress.shouldBeEmpty()
            delegate.stepList shouldHaveSize 1
        }

    @Test
    fun `events, receipts, findings and usage are delegated and are not progress`() =
        runTest {
            recorder.event(EventRecord(EventId("evt_1"), runId, "e", a07, "1", "oracle", "{}", now))
            recorder.receipt(EventReceipt(EventId("evt_1"), runId, a07, true, now, 5))
            recorder.finding(
                FindingRecord(FindingId("fnd_1"), runId, null, "s", a07, FindingClass.BACKEND, null, null, null, "n", emptyList()),
            )
            recorder.usage(UsageRecord(runId, a07, 1, 2, 0, null, 1))

            progress.shouldBeEmpty()
            delegate.eventList shouldHaveSize 1
            delegate.receiptList shouldHaveSize 1
            delegate.findingList shouldHaveSize 1
            delegate.usageList shouldHaveSize 1
        }

    @Test
    fun `no progress is reported when the delegate rejects the record`() =
        runTest {
            val failing =
                ProgressTrackingRecorder(
                    object : EvidenceRecorder by delegate {
                        override suspend fun step(record: StepRecord): Unit = throw IllegalStateException("database locked")
                    },
                    progress::add,
                )

            shouldThrow<IllegalStateException> { failing.step(step(a07)) }
            progress.shouldBeEmpty()
        }
}
