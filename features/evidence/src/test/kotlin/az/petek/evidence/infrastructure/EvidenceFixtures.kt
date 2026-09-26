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

package az.petek.evidence.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResource
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.domain.Verdict
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.time.Instant

/** Record builders with realistic defaults; every test overrides only what it is about. */
object EvidenceFixtures {
    val RUN = RunId("run_1")
    val OTHER_RUN = RunId("run_2")

    /** Deliberately not on a millisecond boundary, to prove instants survive the database unchanged. */
    val T0: Instant = Instant.parse("2026-01-01T10:00:00.123456789Z")

    val A01 = AgentId("a01")
    val A07 = AgentId("a07")

    fun at(seconds: Long): Instant = T0.plusSeconds(seconds)

    /** Opens `<dir>/petek.db`, builds the store on it and closes the database afterwards. */
    fun withStore(
        dir: Path,
        block: suspend (store: SqliteEvidenceStore, db: SqliteDatabase) -> Unit,
    ): TestResult =
        runTest {
            SqliteDatabase.open(dir.resolve("petek.db")).use { db -> block(SqliteEvidenceStore(db), db) }
        }

    fun run(
        id: RunId = RUN,
        startedAt: Instant = T0,
        repeatGroup: String? = null,
        repeatIndex: Int? = null,
    ) = RunRecord(
        runId = id,
        runTag = RunTag("k7x2"),
        campaignHash = "sha256:abc",
        campaignName = "KadroHR elan testi",
        seed = 42,
        target = "https://staging.example.test",
        startedAt = startedAt,
        repeatGroup = repeatGroup,
        repeatIndex = repeatIndex,
    )

    fun resource(
        externalId: String,
        createdAt: Instant = T0,
        runId: RunId = RUN,
        kind: String = "company",
    ) = RunResource(runId = runId, kind = kind, externalId = externalId, createdAt = createdAt)

    fun step(
        id: String,
        startedAt: Instant = T0,
        runId: RunId = RUN,
        status: StepStatus = StepStatus.PASSED,
    ) = StepRecord(
        stepId = StepId(id),
        runId = runId,
        agentId = A07,
        scenarioStep = "announce",
        kind = StepKind.DO,
        action = "click #12 \"Elan yarat\"",
        llmReason = "Elan yaratmaq düyməsi görünür",
        startedAt = startedAt,
        endedAt = startedAt.plusMillis(1500),
        durationMs = 1500,
        status = status,
        detail = "ok",
        correlationId = CorrelationId("cor_$id"),
    )

    fun harnessStep(id: String) =
        step(id).copy(agentId = null, kind = StepKind.SYSTEM, llmReason = null, detail = null, status = StepStatus.ERROR)

    fun event(
        id: String,
        t0: Instant = T0,
        runId: RunId = RUN,
    ) = EventRecord(
        eventId = EventId(id),
        runId = runId,
        name = "announcement_created",
        emitter = A01,
        objectId = "4711",
        objectIdSource = "url_regex",
        payloadJson = """{"title":"Salam, komanda!"}""",
        t0 = t0,
    )

    fun receipt(
        eventId: String,
        receiver: AgentId,
        t1: Instant?,
        runId: RunId = RUN,
    ) = EventReceipt(
        eventId = EventId(eventId),
        runId = runId,
        receiver = receiver,
        received = t1 != null,
        t1 = t1,
        latencyMs = t1?.let { 250L },
    )

    fun artifact(
        id: String,
        runId: RunId = RUN,
        type: ArtifactType = ArtifactType.SCREENSHOT,
    ) = ArtifactRecord(
        artifactId = ArtifactId(id),
        runId = runId,
        stepId = StepId("stp_1"),
        type = type,
        relativePath = "$runId/a07/0001-${type.name.lowercase()}.${type.extension}",
        sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        sizeBytes = 5,
    )

    fun assertion(
        type: String,
        runId: RunId = RUN,
        artifactIds: List<ArtifactId> = listOf(ArtifactId("art_1"), ArtifactId("art_2")),
    ) = AssertionRecord(
        stepId = StepId("stp_1"),
        runId = runId,
        agentId = A07,
        scenarioStep = "announce",
        type = type,
        source = EvidenceSource.RECEIVER,
        expected = "Salam, komanda!",
        observed = "Salam, komanda!",
        verdict = Verdict.PASSED,
        latencyMs = 250,
        note = "seen within budget",
        artifactIds = artifactIds,
    )

    fun finding(
        id: String,
        runId: RunId = RUN,
    ) = FindingRecord(
        findingId = FindingId(id),
        runId = runId,
        stepId = StepId("stp_1"),
        scenarioStep = "announce",
        agentId = A07,
        findingClass = FindingClass.DELIVERY_UI,
        a = "sent 'Salam'",
        b = "not shown",
        c = "stored",
        note = "C ≠ B: delivery or UI defect",
        artifactIds = listOf(ArtifactId("art_1")),
    )

    fun usage(
        agent: AgentId,
        inputTokens: Long = 1000,
        costUsd: Double? = 0.25,
        runId: RunId = RUN,
    ) = UsageRecord(
        runId = runId,
        agentId = agent,
        inputTokens = inputTokens,
        outputTokens = 200,
        cacheReadTokens = 50,
        costUsd = costUsd,
        calls = 1,
    )
}
