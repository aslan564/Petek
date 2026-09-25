/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.scenarios.domain.EvidenceRef
import az.petek.scenarios.domain.EvidenceRefType
import az.petek.scenarios.domain.ProposalStatus
import az.petek.scenarios.domain.ProposedChange
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.Surprise
import az.petek.scenarios.domain.SurpriseEvidence
import az.petek.scenarios.domain.SurpriseId
import az.petek.scenarios.domain.SurpriseKind
import az.petek.scenarios.domain.TriageCategory
import az.petek.scenarios.domain.TriageFailure
import az.petek.scenarios.domain.TriageRepository
import az.petek.scenarios.domain.TriageVerdict
import az.petek.scenarios.domain.YamlEdit
import az.petek.scenarios.testing.InMemoryTriageRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/** Rules every [TriageRepository] implementation must follow; run against SQLite and the in-memory fake. */
abstract class TriageRepositoryContract {
    protected abstract fun repository(): TriageRepository

    private val run = RunId("run_1")
    private val otherRun = RunId("run_2")
    private val at = Instant.parse("2026-01-01T10:00:00.000000001Z")
    private val version = ScenarioVersionId("scn_1")

    private fun surprise(
        n: Int,
        runId: RunId = run,
        agent: String? = "a0$n",
    ) = Surprise(
        id = SurpriseId("srp_${runId}_$n"),
        runId = runId,
        scenarioStep = "read_announce",
        agentId = agent?.let(::AgentId),
        kind = SurpriseKind.PROBLEM_REPORTED,
        text = "a0$n reported a problem: Elan görünmür",
        evidence =
            SurpriseEvidence(
                stepIds = listOf(StepId("stp_$n"), StepId("stp_${n}b")),
                artifactIds = listOf(ArtifactId("art_$n")),
                findingIds = listOf(FindingId("fnd_$n")),
                facts = listOf("[stp_$n] DO click [3] \"Bildirişlər\" -> PASSED", "[art_$n] screenshot recorded for [stp_$n]"),
            ),
    )

    private fun verdict(
        surprise: Surprise,
        change: ProposedChange? = null,
        category: TriageCategory = TriageCategory.SCENARIO_BUG,
    ) = TriageVerdict(
        surpriseId = surprise.id,
        runId = surprise.runId,
        scenarioVersionId = version,
        category = category,
        rationale = "Mətn qeyri-müəyyəndir.",
        confidence = 0.75,
        basedOn = listOf(EvidenceRef(EvidenceRefType.STEP, "stp_1"), EvidenceRef(EvidenceRefType.ARTIFACT, "art_1")),
        proposedChange = change,
        model = "scripted",
        decidedAt = at,
    )

    private val edits = listOf(YamlEdit("do: \"Bildirişləri aç\"", "do: \"Zəngi aç\"\n    parallel: false"), YamlEdit("a", "b"))

    private fun test(block: suspend (TriageRepository) -> Unit) = runBlocking { block(repository()) }

    @Test
    fun `surprises read back exactly, in the order they were added, per run`() =
        test { repo ->
            val s1 = surprise(1)
            val s2 = surprise(2, agent = null)
            val other = surprise(3, runId = otherRun)

            repo.addSurprises(listOf(s1, other, s2))

            repo.surprises(run) shouldBe listOf(s1, s2)
            repo.surprises(otherRun) shouldBe listOf(other)
            repo.surprises(RunId("run_none")).shouldBeEmpty()
        }

    @Test
    fun `adding a stored surprise again keeps the stored one`() =
        test { repo ->
            val s1 = surprise(1)
            repo.addSurprises(listOf(s1))

            repo.addSurprises(listOf(s1.copy(text = "changed"), surprise(2)))
            repo.addSurprises(emptyList())

            repo.surprises(run) shouldBe listOf(s1, surprise(2))
        }

    @Test
    fun `a verdict with a proposal reads back exactly`() =
        test { repo ->
            val s1 = surprise(1)
            repo.addSurprises(listOf(s1))
            val stored =
                verdict(s1, ProposedChange("Addımı dəqiqləşdir", edits, ProposalStatus.DRAFTED, draftId = ScenarioVersionId("scn_2")))

            repo.saveVerdict(stored)

            repo.verdict(s1.id) shouldBe stored
            repo.verdicts(run) shouldBe listOf(stored)
            repo.verdictsForDraft(ScenarioVersionId("scn_2")) shouldBe listOf(stored)
            repo.verdictsForDraft(ScenarioVersionId("scn_9")).shouldBeEmpty()
        }

    @Test
    fun `verdicts without proposal and with a rejected one read back exactly`() =
        test { repo ->
            val s1 = surprise(1)
            val s2 = surprise(2)
            val plain = verdict(s1, category = TriageCategory.SYSTEM_BUG)
            val rejected =
                verdict(
                    s2,
                    ProposedChange("x", edits, ProposalStatus.REJECTED, rejection = "edit 1 does not occur"),
                    TriageCategory.SYSTEM_BUG,
                )

            repo.saveVerdict(plain)
            repo.saveVerdict(rejected)

            repo.verdicts(run) shouldBe listOf(plain, rejected)
        }

    @Test
    fun `saving a verdict again replaces it in place`() =
        test { repo ->
            val s1 = surprise(1)
            val s2 = surprise(2)
            val pending = verdict(s1, ProposedChange("x", edits, ProposalStatus.PENDING))
            repo.saveVerdict(pending)
            repo.saveVerdict(verdict(s2))

            val drafted = pending.copy(proposedChange = pending.proposedChange!!.drafted(ScenarioVersionId("scn_2")))
            repo.saveVerdict(drafted)

            repo.verdicts(run).map { it.surpriseId } shouldBe listOf(s1.id, s2.id)
            repo.verdict(s1.id) shouldBe drafted
        }

    @Test
    fun `a failure is replaced by a newer one and cleared by a verdict`() =
        test { repo ->
            val s1 = surprise(1)
            repo.saveFailure(TriageFailure(s1.id, run, "invalid answer: category", at))
            repo.saveFailure(TriageFailure(s1.id, run, "LLM unavailable: not logged in", at.plusSeconds(1)))

            repo.failures(run) shouldBe listOf(TriageFailure(s1.id, run, "LLM unavailable: not logged in", at.plusSeconds(1)))

            repo.saveVerdict(verdict(s1))

            repo.failures(run).shouldBeEmpty()
        }

    @Test
    fun `a failed re-triage keeps the earlier verdict`() =
        test { repo ->
            val s1 = surprise(1)
            val decided = verdict(s1)
            repo.saveVerdict(decided)

            repo.saveFailure(TriageFailure(s1.id, run, "invalid answer", at))

            repo.verdict(s1.id) shouldBe decided
            repo.failures(run).map { it.surpriseId } shouldBe listOf(s1.id)
            repo.verdicts(otherRun).shouldBeEmpty()
            repo.failures(otherRun).shouldBeEmpty()
        }
}

class InMemoryTriageRepositoryTest : TriageRepositoryContract() {
    override fun repository(): TriageRepository = InMemoryTriageRepository()
}

class SqliteTriageRepositoryTest : TriageRepositoryContract() {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<SqliteDatabase>()

    override fun repository(): TriageRepository = SqliteTriageRepository(SqliteDatabase.open(dir.resolve("petek.db")).also { opened += it })

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
    }

    @Test
    fun `triage results survive reopening the database`() =
        runBlocking<Unit> {
            val first = repository()
            val surprise =
                Surprise(
                    SurpriseId("srp_1"),
                    RunId("run_1"),
                    "join",
                    null,
                    SurpriseKind.FAILED_STEP,
                    "join failed",
                    SurpriseEvidence(emptyList(), emptyList(), listOf(FindingId("fnd_1")), emptyList()),
                )
            first.addSurprises(listOf(surprise))

            repository().surprises(RunId("run_1")) shouldBe listOf(surprise)
        }
}
