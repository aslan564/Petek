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

package az.petek.scenarios.infrastructure

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.scenarios.domain.ProposedChange
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.Surprise
import az.petek.scenarios.domain.SurpriseEvidence
import az.petek.scenarios.domain.SurpriseId
import az.petek.scenarios.domain.TriageFailure
import az.petek.scenarios.domain.TriageRepository
import az.petek.scenarios.domain.TriageVerdict
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Triage results in SQLite (tables `triage_surprise`, `triage_verdict`, `triage_failure`), built by the composition
 * root from the shared [SqliteDatabase]; construction creates the missing tables (idempotent).
 *
 * Surprises are ordered by insertion (collection order) and never change once stored. A verdict or failure is
 * replaced in place per surprise (keeping its position in the listing); storing a verdict removes the surprise's
 * failure in the same transaction.
 */
class SqliteTriageRepository(
    private val db: SqliteDatabase,
) : TriageRepository {
    init {
        db.createMissing(SurpriseTable, VerdictTable, FailureTable)
    }

    override suspend fun addSurprises(surprises: List<Surprise>) {
        if (surprises.isEmpty()) return
        db.write {
            surprises.forEach { surprise ->
                SurpriseTable.insertIgnore {
                    it[id] = surprise.id
                    it[runId] = surprise.runId
                    it[scenarioStep] = surprise.scenarioStep
                    it[agentId] = surprise.agentId
                    it[kind] = surprise.kind
                    it[text] = surprise.text
                    it[stepIds] = JsonColumns.strings(surprise.evidence.stepIds.map { id -> id.value })
                    it[artifactIds] = JsonColumns.strings(surprise.evidence.artifactIds.map { id -> id.value })
                    it[findingIds] = JsonColumns.strings(surprise.evidence.findingIds.map { id -> id.value })
                    it[facts] = JsonColumns.strings(surprise.evidence.facts)
                }
            }
        }
    }

    override suspend fun surprises(runId: RunId): List<Surprise> =
        db.read {
            SurpriseTable
                .selectAll()
                .where { SurpriseTable.runId eq runId }
                .orderBy(SurpriseTable.seq to SortOrder.ASC)
                .map { it.toSurprise() }
        }

    override suspend fun saveVerdict(verdict: TriageVerdict) {
        db.write {
            FailureTable.deleteWhere { FailureTable.surpriseId eq verdict.surpriseId }
            VerdictTable.upsert(VerdictTable.surpriseId) {
                it[surpriseId] = verdict.surpriseId
                it[runId] = verdict.runId
                it[scenarioVersionId] = verdict.scenarioVersionId
                it[category] = verdict.category
                it[rationale] = verdict.rationale
                it[confidence] = verdict.confidence
                it[basedOn] = JsonColumns.refs(verdict.basedOn)
                it[model] = verdict.model
                it[decidedAt] = verdict.decidedAt
                val change = verdict.proposedChange
                it[proposalSummary] = change?.summary
                it[proposalEdits] = change?.let { c -> JsonColumns.edits(c.edits) }
                it[proposalStatus] = change?.status
                it[proposalRejection] = change?.rejection
                it[draftId] = change?.draftId
            }
        }
    }

    override suspend fun verdict(surpriseId: SurpriseId): TriageVerdict? =
        db.read {
            VerdictTable
                .selectAll()
                .where { VerdictTable.surpriseId eq surpriseId }
                .singleOrNull()
                ?.toVerdict()
        }

    override suspend fun verdicts(runId: RunId): List<TriageVerdict> =
        db.read {
            VerdictTable
                .selectAll()
                .where { VerdictTable.runId eq runId }
                .orderBy(VerdictTable.seq to SortOrder.ASC)
                .map { it.toVerdict() }
        }

    override suspend fun verdictsForDraft(draftId: ScenarioVersionId): List<TriageVerdict> =
        db.read {
            VerdictTable
                .selectAll()
                .where { VerdictTable.draftId eq draftId }
                .orderBy(VerdictTable.seq to SortOrder.ASC)
                .map { it.toVerdict() }
        }

    override suspend fun saveFailure(failure: TriageFailure) {
        db.write {
            FailureTable.upsert(FailureTable.surpriseId) {
                it[surpriseId] = failure.surpriseId
                it[runId] = failure.runId
                it[reason] = failure.reason
                it[failedAt] = failure.failedAt
            }
        }
    }

    override suspend fun failures(runId: RunId): List<TriageFailure> =
        db.read {
            FailureTable
                .selectAll()
                .where { FailureTable.runId eq runId }
                .orderBy(FailureTable.seq to SortOrder.ASC)
                .map {
                    TriageFailure(it[FailureTable.surpriseId], it[FailureTable.runId], it[FailureTable.reason], it[FailureTable.failedAt])
                }
        }

    private fun ResultRow.toSurprise(): Surprise =
        Surprise(
            id = this[SurpriseTable.id],
            runId = this[SurpriseTable.runId],
            scenarioStep = this[SurpriseTable.scenarioStep],
            agentId = this[SurpriseTable.agentId],
            kind = this[SurpriseTable.kind],
            text = this[SurpriseTable.text],
            evidence =
                SurpriseEvidence(
                    stepIds = JsonColumns.strings(this[SurpriseTable.stepIds]).map(::StepId),
                    artifactIds = JsonColumns.strings(this[SurpriseTable.artifactIds]).map(::ArtifactId),
                    findingIds = JsonColumns.strings(this[SurpriseTable.findingIds]).map(::FindingId),
                    facts = JsonColumns.strings(this[SurpriseTable.facts]),
                ),
        )

    private fun ResultRow.toVerdict(): TriageVerdict {
        val status = this[VerdictTable.proposalStatus]
        val change =
            status?.let {
                ProposedChange(
                    summary = this[VerdictTable.proposalSummary].orEmpty(),
                    edits = this[VerdictTable.proposalEdits]?.let(JsonColumns::edits).orEmpty(),
                    status = it,
                    rejection = this[VerdictTable.proposalRejection],
                    draftId = this[VerdictTable.draftId],
                )
            }
        return TriageVerdict(
            surpriseId = this[VerdictTable.surpriseId],
            runId = this[VerdictTable.runId],
            scenarioVersionId = this[VerdictTable.scenarioVersionId],
            category = this[VerdictTable.category],
            rationale = this[VerdictTable.rationale],
            confidence = this[VerdictTable.confidence],
            basedOn = JsonColumns.refs(this[VerdictTable.basedOn]),
            proposedChange = change,
            model = this[VerdictTable.model],
            decidedAt = this[VerdictTable.decidedAt],
        )
    }
}
