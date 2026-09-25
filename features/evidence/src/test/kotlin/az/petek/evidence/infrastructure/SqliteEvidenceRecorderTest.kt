/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.evidence.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.StepId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.evidence.infrastructure.EvidenceFixtures.A01
import az.petek.evidence.infrastructure.EvidenceFixtures.A07
import az.petek.evidence.infrastructure.EvidenceFixtures.OTHER_RUN
import az.petek.evidence.infrastructure.EvidenceFixtures.RUN
import az.petek.evidence.infrastructure.EvidenceFixtures.T0
import az.petek.evidence.infrastructure.EvidenceFixtures.artifact
import az.petek.evidence.infrastructure.EvidenceFixtures.assertion
import az.petek.evidence.infrastructure.EvidenceFixtures.at
import az.petek.evidence.infrastructure.EvidenceFixtures.event
import az.petek.evidence.infrastructure.EvidenceFixtures.finding
import az.petek.evidence.infrastructure.EvidenceFixtures.harnessStep
import az.petek.evidence.infrastructure.EvidenceFixtures.receipt
import az.petek.evidence.infrastructure.EvidenceFixtures.step
import az.petek.evidence.infrastructure.EvidenceFixtures.withStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteEvidenceRecorderTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `steps round-trip with every field, including a harness step without agent or reason`() =
        withStore(dir) { store, _ ->
            val agentStep = step("stp_1")
            val systemStep = harnessStep("stp_2")

            store.step(agentStep)
            store.step(systemStep)

            store.steps(RUN) shouldContainExactly listOf(agentStep, systemStep)
        }

    @Test
    fun `every step kind, step status and finding class round-trips by name`() =
        withStore(dir) { store, _ ->
            val steps =
                StepKind.entries.flatMap { kind ->
                    StepStatus.entries.map { status -> step("stp_${kind}_$status").copy(kind = kind, status = status) }
                }
            val findings = FindingClass.entries.map { finding("fnd_$it").copy(findingClass = it) }

            steps.forEach { store.step(it) }
            findings.forEach { store.finding(it) }

            store.steps(RUN) shouldContainExactly steps
            store.findings(RUN) shouldContainExactly findings
        }

    @Test
    fun `events and receipts round-trip, including a receipt that was never received`() =
        withStore(dir) { store, _ ->
            val announced = event("evt_1")
            val noObjectId = event("evt_2", t0 = at(1)).copy(objectId = null, objectIdSource = null)
            val seen = receipt("evt_1", A07, t1 = at(1))
            val missed = receipt("evt_1", A01, t1 = null)

            store.event(announced)
            store.event(noObjectId)
            store.receipt(seen)
            store.receipt(missed)

            store.events(RUN) shouldContainExactly listOf(announced, noObjectId)
            store.receipts(RUN) shouldContainExactly listOf(seen, missed)
        }

    @Test
    fun `artifacts of every type round-trip`() =
        withStore(dir) { store, _ ->
            val artifacts = ArtifactType.entries.mapIndexed { i, type -> artifact("art_$i", type = type) }

            artifacts.forEach { store.artifact(it) }

            store.artifacts(RUN) shouldContainExactly artifacts
        }

    @Test
    fun `assertions round-trip with their artifact links, optional fields and every source and verdict`() =
        withStore(dir) { store, _ ->
            val linked = assertion("visible_text")
            val bare =
                assertion("oracle", artifactIds = emptyList())
                    .copy(agentId = null, observed = null, latencyMs = null, note = null)
            val variants =
                EvidenceSource.entries.flatMap { source ->
                    Verdict.entries.map { verdict -> assertion("count").copy(source = source, verdict = verdict) }
                }

            (listOf(linked, bare) + variants).forEach { store.assertion(it) }

            store.assertions(RUN) shouldContainExactly listOf(linked, bare) + variants
        }

    @Test
    fun `findings round-trip, with and without a step, agent or A-B-C values`() =
        withStore(dir) { store, _ ->
            val full = finding("fnd_1")
            val runLevel =
                finding("fnd_2").copy(
                    stepId = null,
                    agentId = null,
                    findingClass = FindingClass.AGENT_FAILURE,
                    a = null,
                    b = null,
                    c = null,
                    artifactIds = emptyList(),
                )

            store.finding(full)
            store.finding(runLevel)

            store.findings(RUN) shouldContainExactly listOf(full, runLevel)
        }

    @Test
    fun `text with quotes, newlines and non-ASCII characters is stored verbatim`() =
        withStore(dir) { store, _ ->
            val tricky = "Elan: \"Salam\", 'komanda'\nŞəki → Gəncə; DROP TABLE step; -- ✓"
            val record = step("stp_1").copy(action = tricky, detail = tricky, llmReason = tricky)
            val ids = listOf(ArtifactId("art \"quoted\""), ArtifactId("art,with,commas"), ArtifactId("art_ə"))

            store.step(record)
            store.assertion(assertion("visible_text", artifactIds = ids))

            store.steps(RUN).single() shouldBe record
            store.assertions(RUN).single().artifactIds shouldContainExactly ids
        }

    @Test
    fun `steps are ordered by start time, then by insertion order`() =
        withStore(dir) { store, _ ->
            val third = step("stp_a", startedAt = at(3))
            val firstTie = step("stp_b", startedAt = at(1))
            val second = step("stp_c", startedAt = at(2))
            val secondTie = step("stp_d", startedAt = at(1))
            val nanosLater = step("stp_e", startedAt = at(1).plusNanos(1))

            listOf(third, firstTie, second, secondTie, nanosLater).forEach { store.step(it) }

            store.steps(RUN).map { it.stepId.value } shouldContainExactly listOf("stp_b", "stp_d", "stp_e", "stp_c", "stp_a")
        }

    @Test
    fun `events are ordered by t0, then by insertion order`() =
        withStore(dir) { store, _ ->
            listOf(event("evt_late", t0 = at(5)), event("evt_early", t0 = at(1)), event("evt_tie", t0 = at(1)))
                .forEach { store.event(it) }

            store.events(RUN).map { it.eventId.value } shouldContainExactly listOf("evt_early", "evt_tie", "evt_late")
        }

    @Test
    fun `receipts are ordered by t1 and receipts never received come last in insertion order`() =
        withStore(dir) { store, _ ->
            val a02 = AgentId("a02")
            val a03 = AgentId("a03")
            val a04 = AgentId("a04")
            listOf(
                receipt("evt_1", A01, t1 = at(2)),
                receipt("evt_1", a02, t1 = null),
                receipt("evt_1", a03, t1 = at(1)),
                receipt("evt_1", a04, t1 = null),
            ).forEach { store.receipt(it) }

            store.receipts(RUN).map { it.receiver } shouldContainExactly listOf(a03, A01, a02, a04)
        }

    @Test
    fun `records without a timestamp come back in the order they were recorded`() =
        withStore(dir) { store, _ ->
            listOf("art_c", "art_a", "art_b").forEach { store.artifact(artifact(it)) }
            listOf("latency_max", "count", "visible_text").forEach { store.assertion(assertion(it)) }
            listOf("fnd_z", "fnd_x").forEach { store.finding(finding(it)) }

            store.artifacts(RUN).map { it.artifactId.value } shouldContainExactly listOf("art_c", "art_a", "art_b")
            store.assertions(RUN).map { it.type } shouldContainExactly listOf("latency_max", "count", "visible_text")
            store.findings(RUN).map { it.findingId.value } shouldContainExactly listOf("fnd_z", "fnd_x")
        }

    @Test
    fun `queries only return records of the requested run`() =
        withStore(dir) { store, _ ->
            store.step(step("stp_1"))
            store.step(step("stp_1", runId = OTHER_RUN))
            store.event(event("evt_1", runId = OTHER_RUN))
            store.receipt(receipt("evt_1", A07, t1 = T0, runId = OTHER_RUN))
            store.artifact(artifact("art_1", runId = OTHER_RUN))
            store.assertion(assertion("count", runId = OTHER_RUN))
            store.finding(finding("fnd_1", runId = OTHER_RUN))
            store.usage(EvidenceFixtures.usage(A07, runId = OTHER_RUN))

            store.steps(RUN) shouldHaveSize 1
            store.events(RUN).shouldBeEmpty()
            store.receipts(RUN).shouldBeEmpty()
            store.artifacts(RUN).shouldBeEmpty()
            store.assertions(RUN).shouldBeEmpty()
            store.findings(RUN).shouldBeEmpty()
            store.usage(RUN).shouldBeEmpty()
            store.steps(OTHER_RUN) shouldHaveSize 1
        }

    @Test
    fun `recording a step id again replaces it and keeps its place`() =
        withStore(dir) { store, _ ->
            store.step(step("stp_1"))
            store.step(step("stp_2"))
            val corrected = step("stp_1", status = StepStatus.FAILED).copy(detail = "timeout")

            store.step(corrected)

            store.steps(RUN) shouldContainExactly listOf(corrected, step("stp_2"))
        }

    @Test
    fun `recording the same event, receipt, artifact or finding twice stores it once`() =
        withStore(dir) { store, _ ->
            repeat(2) {
                store.event(event("evt_1"))
                store.receipt(receipt("evt_1", A07, t1 = at(1)))
                store.artifact(artifact("art_1"))
                store.finding(finding("fnd_1"))
            }
            store.receipt(receipt("evt_1", A07, t1 = at(2)))

            store.events(RUN) shouldHaveSize 1
            store.receipts(RUN).single().t1 shouldBe at(2)
            store.artifacts(RUN) shouldHaveSize 1
            store.findings(RUN) shouldHaveSize 1
        }

    @Test
    fun `assertions have no id, so every recorded assertion is kept`() =
        withStore(dir) { store, _ ->
            repeat(3) { store.assertion(assertion("visible_text")) }

            store.assertions(RUN) shouldHaveSize 3
        }

    @Test
    fun `200 concurrent step writes from many coroutines are all stored`() =
        withStore(dir) { store, _ ->
            val steps = (1..200).map { i -> step("stp_$i", startedAt = at(i.toLong())) }

            withContext(Dispatchers.Default) {
                steps.map { record -> async { store.step(record) } }.awaitAll()
            }

            store.steps(RUN) shouldContainExactly steps
        }

    @Test
    fun `concurrent writers of different record types do not lose anything`() =
        withStore(dir) { store, _ ->
            withContext(Dispatchers.Default) {
                (1..50)
                    .flatMap { i ->
                        listOf(
                            async { store.step(step("stp_$i")) },
                            async { store.event(event("evt_$i")) },
                            async { store.artifact(artifact("art_$i")) },
                            async { store.assertion(assertion("count")) },
                            async { store.finding(finding("fnd_$i")) },
                        )
                    }.awaitAll()
            }

            store.steps(RUN) shouldHaveSize 50
            store.events(RUN) shouldHaveSize 50
            store.artifacts(RUN) shouldHaveSize 50
            store.assertions(RUN) shouldHaveSize 50
            store.findings(RUN) shouldHaveSize 50
        }

    @Test
    fun `evidence survives closing and reopening the database`() =
        runTest {
            val record = step("stp_1")
            SqliteDatabase.open(dir.resolve("petek.db")).use { db -> SqliteEvidenceStore(db).step(record) }

            SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
                val reopened = SqliteEvidenceStore(db)
                reopened.steps(RUN) shouldContainExactly listOf(record)
                reopened.step(step("stp_2", startedAt = at(1)))
                reopened.steps(RUN).map { it.stepId } shouldContainExactlyInAnyOrder listOf(StepId("stp_1"), StepId("stp_2"))
            }
        }
}
