/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.infrastructure

import az.petek.browser.domain.RealtimeTransport
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.StepId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.explorer.domain.CoveredIdea
import az.petek.explorer.domain.EventHeader
import az.petek.explorer.domain.ExplorationBudget
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationFinding
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRecord
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.ExplorationSummary
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.domain.ModelCounts
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.RealtimeObservation
import az.petek.explorer.domain.ScenarioDraft
import az.petek.explorer.domain.Severity
import az.petek.explorer.domain.SkippedIdea
import az.petek.explorer.domain.TestIdea
import az.petek.explorer.domain.TestPattern
import az.petek.explorer.domain.Unknown
import az.petek.explorer.support.Models
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Instant

class SqliteExplorationRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val id = ExplorationId("exp_1")
    private val at: Instant = Instant.parse("2026-01-01T10:00:00.123456789Z")

    private fun withRepository(block: suspend (SqliteExplorationRepository) -> Unit) =
        runBlocking {
            SqliteDatabase.open(dir.resolve("petek.db")).use { db -> block(SqliteExplorationRepository(db)) }
        }

    private fun record(
        explorationId: ExplorationId = id,
        target: URI = Models.TARGET,
        startedAt: Instant = at,
    ) = ExplorationRecord(
        explorationId,
        ExplorationRequest(
            target,
            "Müraciət axınını yoxla",
            ExplorationBudget(12, 5, 3),
            setOf(ExplorationPhase.ANONYMOUS, ExplorationPhase.TRIAL_TOUCH),
            allowWrites = true,
        ),
        ExplorationStatus.RUNNING,
        startedAt,
    )

    private val summary =
        ExplorationSummary(
            status = ExplorationStatus.TIMED_OUT,
            counts = ModelCounts(5, 4, 5, 1, 2, 3),
            pagesVisitedByRole = mapOf("anonymous" to 2, "admin" to 3),
            llmCalls = 5,
            llmAnswersRejected = 1,
            durationMs = 61_000,
            pageBudgetReached = true,
            phasesRun = listOf(ExplorationPhase.ANONYMOUS),
            phasesSkipped = mapOf(ExplorationPhase.TRIAL_TOUCH to "allowWrites is false"),
            notes = listOf("robots.txt keeps 1 path rule(s) out of the exploration"),
        )

    private val finding =
        ExplorationFinding(
            FindingId("fnd_1"),
            FindingKind.BROKEN_LINK,
            Severity.MEDIUM,
            "https://kadro.test/old",
            "404",
            "anonymous",
            listOf(ArtifactId("art_1")),
        )

    @Test
    fun `an exploration record goes from running to finished with its summary`() =
        withRepository { repository ->
            repository.create(record())
            repository.find(id) shouldBe record()

            repository.finish(id, ExplorationStatus.TIMED_OUT, at.plusSeconds(61), summary, 3)

            repository.find(id) shouldBe
                record().copy(status = ExplorationStatus.TIMED_OUT, endedAt = at.plusSeconds(61), summary = summary, modelVersion = 3)
            shouldThrow<IllegalArgumentException> { repository.create(record()) }
            shouldThrow<IllegalArgumentException> { repository.finish(ExplorationId("exp_9"), ExplorationStatus.FAILED, at, summary, null) }
            repository.find(ExplorationId("exp_9")).shouldBeNull()
        }

    @Test
    fun `explorations are listed newest first, per target when asked`() =
        withRepository { repository ->
            repository.create(record(ExplorationId("exp_1"), startedAt = at))
            repository.create(record(ExplorationId("exp_2"), URI("https://other.test"), at.plusSeconds(10)))
            repository.create(record(ExplorationId("exp_3"), URI("https://KADRO.test/"), at.plusSeconds(20)))

            repository.list().map { it.id.value } shouldContainExactly listOf("exp_3", "exp_2", "exp_1")
            repository.list(Models.TARGET).map { it.id.value } shouldContainExactly listOf("exp_3", "exp_1")
            repository.list(limit = 1).map { it.id.value } shouldContainExactly listOf("exp_3")
        }

    @Test
    fun `a full site model survives the round trip and versions are unique per target`() =
        withRepository { repository ->
            val v1 =
                Models.kadro(1).copy(
                    createdAt = at,
                    unknowns = listOf(Unknown("u1", "Kim görür?", "trial", "tickets", Provenance.INFERRED, listOf(ArtifactId("art_9")))),
                    realtime =
                        listOf(
                            RealtimeObservation(
                                RealtimeTransport.POLLING,
                                "Polling GET /x",
                                setOf("tickets"),
                                setOf("admin"),
                                Provenance.OBSERVED,
                                emptyList(),
                            ),
                        ),
                )
            repository.saveModel(v1)
            val v2 = Models.kadro(2).copy(explorationId = ExplorationId("exp_2"), partial = true)
            repository.saveModel(v2)

            repository.model(id) shouldBe v1
            repository.model(Models.TARGET, 1) shouldBe v1
            repository.model(URI("https://kadro.test/"), 2)!!.explorationId shouldBe ExplorationId("exp_2")
            repository.latestVersion(Models.TARGET) shouldBe 2
            repository.latestModel(Models.TARGET) shouldBe v2
            repository.latestModel(URI("https://nothing.test")).shouldBeNull()
            repository.latestVersion(URI("https://nothing.test")) shouldBe 0
            repository.versions(Models.TARGET) shouldContainExactly listOf(1, 2)
            shouldThrow<IllegalArgumentException> { repository.saveModel(Models.kadro(2).copy(explorationId = ExplorationId("exp_3"))) }
            shouldThrow<IllegalArgumentException> { repository.saveModel(Models.kadro(5).copy(explorationId = id)) }
        }

    @Test
    fun `findings, artifacts and drafts are stored per exploration`() =
        withRepository { repository ->
            val artifact =
                ArtifactRecord(
                    ArtifactId("art_1"),
                    id.evidenceKey,
                    StepId("stp_1"),
                    ArtifactType.SCREENSHOT,
                    "exp_1/anonymous/0001-screenshot.png",
                    "abc",
                    42,
                )
            val idea = TestIdea(TestPattern.RACE, "ticket-approve", "two approvers", 50, listOf("manager"))
            val draft =
                ScenarioDraft(
                    "drf_1",
                    id,
                    1,
                    Models.TARGET,
                    "explorer-kadro-v1",
                    "campaign:\n  name: \"x\"\n",
                    listOf(CoveredIdea(idea, listOf("ticket-approve-race"))),
                    listOf(SkippedIdea(idea.copy(pattern = TestPattern.BOUNDARY), "not observed")),
                    at,
                )

            repository.saveFinding(id, finding)
            repository.saveArtifact(artifact)
            repository.saveArtifact(artifact.copy(sizeBytes = 43))
            repository.saveDraft(draft)

            repository.findings(id) shouldContainExactly listOf(finding)
            repository.findings(ExplorationId("exp_2")) shouldBe emptyList()
            repository.artifact(ArtifactId("art_1")) shouldBe artifact.copy(sizeBytes = 43)
            repository.artifacts(id) shouldContainExactly listOf(artifact.copy(sizeBytes = 43))
            repository.draft("drf_1") shouldBe draft
            repository.drafts(id) shouldContainExactly listOf(draft)
            shouldThrow<IllegalArgumentException> { repository.saveFinding(id, finding) }
            shouldThrow<IllegalArgumentException> { repository.saveDraft(draft) }
        }

    @Test
    fun `every kind of event is stored, replayed in order and numbered without duplicates`() =
        withRepository { repository ->
            val model = Models.kadro()

            fun header(seq: Long) = EventHeader(id, seq, at.plusMillis(seq))
            val events =
                listOf(
                    ExplorationEvent.Started(header(1), Models.TARGET, listOf(ExplorationPhase.ANONYMOUS), "x", ExplorationBudget(), false),
                    ExplorationEvent.PhaseStarted(header(2), ExplorationPhase.ANONYMOUS, listOf("anonymous")),
                    ExplorationEvent.PhaseSkipped(header(3), ExplorationPhase.ROLE_BASED, "no sessions"),
                    ExplorationEvent.PageVisited(
                        header(4),
                        "anonymous",
                        "https://kadro.test/login",
                        "/login",
                        "Daxil ol",
                        200,
                        120,
                        ArtifactId("art_1"),
                    ),
                    ExplorationEvent.PageVisited(header(5), "anonymous", "https://kadro.test/x", "/x", "X", null, null, null),
                    ExplorationEvent.ActionDiscovered(header(6), model.actions.first { it.trial != null }),
                    ExplorationEvent.FindingRecorded(header(7), finding),
                    ExplorationEvent.UnknownRaised(header(8), Unknown("u1", "Q?", "", null, Provenance.OBSERVED, emptyList())),
                    ExplorationEvent.ModelUpdated(header(9), ModelCounts(1, 2, 3, 4, 5, 6)),
                    ExplorationEvent.DraftReady(header(10), "drf_1", "draft", 3, 2),
                    ExplorationEvent.Finished(header(11), summary, 4),
                    ExplorationEvent.Failed(header(12), "boom", null),
                )

            events.forEach { repository.append(it) }

            repository.events(id) shouldBe events
            repository.events(id, afterSeq = 10) shouldBe events.takeLast(2)
            repository.lastSeq(id) shouldBe 12
            repository.lastSeq(ExplorationId("exp_2")) shouldBe 0
            shouldThrow<IllegalArgumentException> { repository.append(events.first()) }
        }

    @Test
    fun `tables are created once and reopening the database keeps everything`() {
        withRepository { it.create(record()) }
        withRepository { repository -> repository.find(id) shouldBe record() }
    }
}
