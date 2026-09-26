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

package az.petek.explorer.infrastructure

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.StepId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.explorer.domain.EventHeader
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationFinding
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationRecord
import az.petek.explorer.domain.ExplorationRepository
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.ExplorationSummary
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.domain.ScenarioDraft
import az.petek.explorer.domain.Severity
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.TargetKey
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.time.Instant

/**
 * The explorer's [ExplorationRepository] in the process's SQLite database, built by the composition root from the
 * shared [SqliteDatabase]. Construction creates the missing tables (`exploration`, `site_model_version`,
 * `exploration_finding`, `exploration_event`, `exploration_artifact`, `scenario_draft`) and is idempotent.
 *
 * Writes go through the database's single writer, so "read the latest version, then insert the next" and "append
 * the next event" cannot interleave within the process; unique indexes enforce the same across processes
 * (a conflicting model version, event number, exploration, finding or draft id is an [IllegalArgumentException]).
 * Models, summaries and events are stored as JSON; everything else in plain columns (see ExplorationTables.kt).
 */
class SqliteExplorationRepository(
    private val db: SqliteDatabase,
) : ExplorationRepository {
    init {
        db.createMissing(*explorationTables)
    }

    // ---- records ----

    override suspend fun create(record: ExplorationRecord) {
        val inserted =
            db.write {
                ExplorationTable
                    .insertIgnore {
                        it[explorationId] = record.id.value
                        it[targetKey] = TargetKey.of(record.request.target)
                        it[target] = record.request.target.toString()
                        it[requestJson] = ExplorationJsonMapper.encodeRequest(record.request)
                        it[status] = record.status.name
                        it[startedAt] = InstantText.encode(record.startedAt)
                        it[endedAt] = record.endedAt?.let(InstantText::encode)
                        it[summaryJson] = record.summary?.let(ExplorationJsonMapper::encodeSummary)
                        it[modelVersion] = record.modelVersion
                    }.insertedCount
            }
        require(inserted == 1) { "Exploration ${record.id} already exists" }
    }

    override suspend fun finish(
        id: ExplorationId,
        status: ExplorationStatus,
        endedAt: Instant,
        summary: ExplorationSummary,
        modelVersion: Int?,
    ) {
        val updated =
            db.write {
                ExplorationTable.update({ ExplorationTable.explorationId eq id.value }) {
                    it[ExplorationTable.status] = status.name
                    it[ExplorationTable.endedAt] = InstantText.encode(endedAt)
                    it[summaryJson] = ExplorationJsonMapper.encodeSummary(summary)
                    it[ExplorationTable.modelVersion] = modelVersion
                }
            }
        require(updated == 1) { "Cannot finish unknown exploration $id" }
    }

    override suspend fun find(id: ExplorationId): ExplorationRecord? =
        db.read {
            ExplorationTable
                .selectAll()
                .where { ExplorationTable.explorationId eq id.value }
                .firstOrNull()
                ?.toRecord()
        }

    override suspend fun list(
        target: URI?,
        limit: Int,
    ): List<ExplorationRecord> =
        db.read {
            val query = ExplorationTable.selectAll()
            if (target != null) query.where { ExplorationTable.targetKey eq TargetKey.of(target) }
            query
                .orderBy(ExplorationTable.startedAt to SortOrder.DESC, ExplorationTable.seq to SortOrder.DESC)
                .limit(limit)
                .map { it.toRecord() }
        }

    // ---- site models ----

    override suspend fun saveModel(model: SiteModel) {
        val key = TargetKey.of(model.target)
        db.write {
            val taken =
                SiteModelTable
                    .selectAll()
                    .where {
                        ((SiteModelTable.targetKey eq key) and (SiteModelTable.version eq model.version)) or
                            (SiteModelTable.explorationId eq model.explorationId.value)
                    }.any()
            require(!taken) { "Site model v${model.version} of $key or a model of ${model.explorationId} already exists" }
            SiteModelTable.insert {
                it[explorationId] = model.explorationId.value
                it[targetKey] = key
                it[version] = model.version
                it[createdAt] = InstantText.encode(model.createdAt)
                it[modelJson] = ExplorationJsonMapper.encodeModel(model)
            }
        }
    }

    override suspend fun latestVersion(target: URI): Int = db.read { latestVersion(TargetKey.of(target)) } ?: 0

    override suspend fun model(
        target: URI,
        version: Int,
    ): SiteModel? =
        db.read {
            SiteModelTable
                .selectAll()
                .where { (SiteModelTable.targetKey eq TargetKey.of(target)) and (SiteModelTable.version eq version) }
                .firstOrNull()
                ?.let { ExplorationJsonMapper.decodeModel(it[SiteModelTable.modelJson]) }
        }

    override suspend fun model(explorationId: ExplorationId): SiteModel? =
        db.read {
            SiteModelTable
                .selectAll()
                .where { SiteModelTable.explorationId eq explorationId.value }
                .firstOrNull()
                ?.let { ExplorationJsonMapper.decodeModel(it[SiteModelTable.modelJson]) }
        }

    override suspend fun versions(target: URI): List<Int> =
        db.read {
            SiteModelTable
                .selectAll()
                .where { SiteModelTable.targetKey eq TargetKey.of(target) }
                .orderBy(SiteModelTable.version to SortOrder.ASC)
                .map { it[SiteModelTable.version] }
        }

    private fun JdbcTransaction.latestVersion(key: String): Int? =
        SiteModelTable
            .selectAll()
            .where { SiteModelTable.targetKey eq key }
            .orderBy(SiteModelTable.version to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SiteModelTable.version)

    // ---- findings ----

    override suspend fun saveFinding(
        explorationId: ExplorationId,
        finding: ExplorationFinding,
    ) {
        val inserted =
            db.write {
                FindingTable
                    .insertIgnore {
                        it[FindingTable.explorationId] = explorationId.value
                        it[findingId] = finding.id.value
                        it[kind] = finding.kind.name
                        it[severity] = finding.severity.name
                        it[pageUrl] = finding.pageUrl
                        it[detail] = finding.detail
                        it[role] = finding.role
                        it[evidenceJson] = ExplorationJsonMapper.encodeIds(finding.evidence)
                    }.insertedCount
            }
        require(inserted == 1) { "Finding ${finding.id} already exists" }
    }

    override suspend fun findings(explorationId: ExplorationId): List<ExplorationFinding> =
        db.read {
            FindingTable
                .selectAll()
                .where { FindingTable.explorationId eq explorationId.value }
                .orderBy(FindingTable.seq to SortOrder.ASC)
                .map { it.toFinding() }
        }

    // ---- events ----

    override suspend fun append(event: ExplorationEvent) {
        val inserted =
            db.write {
                EventTable
                    .insertIgnore {
                        it[explorationId] = event.explorationId.value
                        it[eventSeq] = event.header.seq
                        it[type] = ExplorationJsonMapper.typeOf(event)
                        it[at] = InstantText.encode(event.header.at)
                        it[payloadJson] = ExplorationJsonMapper.encodeEvent(event)
                    }.insertedCount
            }
        require(inserted == 1) { "Event ${event.header.seq} of ${event.explorationId} already exists" }
    }

    override suspend fun events(
        explorationId: ExplorationId,
        afterSeq: Long,
    ): List<ExplorationEvent> =
        db.read {
            EventTable
                .selectAll()
                .where { (EventTable.explorationId eq explorationId.value) and (EventTable.eventSeq greater afterSeq) }
                .orderBy(EventTable.eventSeq to SortOrder.ASC)
                .map { row ->
                    val header = EventHeader(explorationId, row[EventTable.eventSeq], InstantText.decode(row[EventTable.at]))
                    ExplorationJsonMapper.decodeEvent(header, row[EventTable.payloadJson])
                }
        }

    override suspend fun lastSeq(explorationId: ExplorationId): Long =
        db.read {
            EventTable
                .selectAll()
                .where { EventTable.explorationId eq explorationId.value }
                .orderBy(EventTable.eventSeq to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(EventTable.eventSeq)
        } ?: 0

    // ---- artifacts ----

    override suspend fun saveArtifact(record: ArtifactRecord) {
        db.write {
            ArtifactTable.deleteWhere { ArtifactTable.artifactId eq record.artifactId.value }
            ArtifactTable.insert {
                it[artifactId] = record.artifactId.value
                it[explorationId] = record.runId.value
                it[stepId] = record.stepId.value
                it[type] = record.type.name
                it[relativePath] = record.relativePath
                it[sha256] = record.sha256
                it[sizeBytes] = record.sizeBytes
            }
        }
    }

    override suspend fun artifact(id: ArtifactId): ArtifactRecord? =
        db.read {
            ArtifactTable
                .selectAll()
                .where { ArtifactTable.artifactId eq id.value }
                .firstOrNull()
                ?.toArtifact()
        }

    override suspend fun artifacts(explorationId: ExplorationId): List<ArtifactRecord> =
        db.read {
            ArtifactTable
                .selectAll()
                .where { ArtifactTable.explorationId eq explorationId.value }
                .orderBy(ArtifactTable.seq to SortOrder.ASC)
                .map { it.toArtifact() }
        }

    // ---- drafts ----

    override suspend fun saveDraft(draft: ScenarioDraft) {
        val inserted =
            db.write {
                DraftTable
                    .insertIgnore {
                        it[draftId] = draft.id
                        it[explorationId] = draft.explorationId.value
                        it[targetKey] = TargetKey.of(draft.target)
                        it[target] = draft.target.toString()
                        it[modelVersion] = draft.modelVersion
                        it[name] = draft.name
                        it[yaml] = draft.yaml
                        it[coveredJson] = ExplorationJsonMapper.encodeCovered(draft.covered)
                        it[skippedJson] = ExplorationJsonMapper.encodeSkipped(draft.skipped)
                        it[createdAt] = InstantText.encode(draft.createdAt)
                    }.insertedCount
            }
        require(inserted == 1) { "Scenario draft ${draft.id} already exists" }
    }

    override suspend fun draft(id: String): ScenarioDraft? =
        db.read {
            DraftTable
                .selectAll()
                .where { DraftTable.draftId eq id }
                .firstOrNull()
                ?.toDraft()
        }

    override suspend fun drafts(explorationId: ExplorationId): List<ScenarioDraft> =
        db.read {
            DraftTable
                .selectAll()
                .where { DraftTable.explorationId eq explorationId.value }
                .orderBy(DraftTable.seq to SortOrder.ASC)
                .map { it.toDraft() }
        }
}

private fun ResultRow.toRecord(): ExplorationRecord =
    ExplorationRecord(
        id = ExplorationId(this[ExplorationTable.explorationId]),
        request = ExplorationJsonMapper.decodeRequest(this[ExplorationTable.requestJson]),
        status = enumValueOf<ExplorationStatus>(this[ExplorationTable.status]),
        startedAt = InstantText.decode(this[ExplorationTable.startedAt]),
        endedAt = this[ExplorationTable.endedAt]?.let(InstantText::decode),
        summary = this[ExplorationTable.summaryJson]?.let(ExplorationJsonMapper::decodeSummary),
        modelVersion = this[ExplorationTable.modelVersion],
    )

private fun ResultRow.toFinding(): ExplorationFinding =
    ExplorationFinding(
        id = FindingId(this[FindingTable.findingId]),
        kind = enumValueOf<FindingKind>(this[FindingTable.kind]),
        severity = enumValueOf<Severity>(this[FindingTable.severity]),
        pageUrl = this[FindingTable.pageUrl],
        detail = this[FindingTable.detail],
        role = this[FindingTable.role],
        evidence = ExplorationJsonMapper.decodeIds(this[FindingTable.evidenceJson]),
    )

private fun ResultRow.toArtifact(): ArtifactRecord =
    ArtifactRecord(
        artifactId = ArtifactId(this[ArtifactTable.artifactId]),
        runId = ExplorationId(this[ArtifactTable.explorationId]).evidenceKey,
        stepId = StepId(this[ArtifactTable.stepId]),
        type = enumValueOf<ArtifactType>(this[ArtifactTable.type]),
        relativePath = this[ArtifactTable.relativePath],
        sha256 = this[ArtifactTable.sha256],
        sizeBytes = this[ArtifactTable.sizeBytes],
    )

private fun ResultRow.toDraft(): ScenarioDraft =
    ScenarioDraft(
        id = this[DraftTable.draftId],
        explorationId = ExplorationId(this[DraftTable.explorationId]),
        modelVersion = this[DraftTable.modelVersion],
        target = URI(this[DraftTable.target]),
        name = this[DraftTable.name],
        yaml = this[DraftTable.yaml],
        covered = ExplorationJsonMapper.decodeCovered(this[DraftTable.coveredJson]),
        skipped = ExplorationJsonMapper.decodeSkipped(this[DraftTable.skippedJson]),
        createdAt = InstantText.decode(this[DraftTable.createdAt]),
    )
