/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.evidence.infrastructure

import az.petek.core.ids.RunId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResource
import az.petek.evidence.domain.RunResult
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Runs and the external resources they created, persisted in SQLite.
 *
 * - [create] refuses a run id that already exists: a run is created exactly once, by the orchestrator.
 * - [finish] refuses an unknown run, so a lost run row surfaces instead of the result silently vanishing.
 * - Resources are a set per run: adding the same (kind, external id) twice keeps the first registration, and
 *   removing a missing resource is a no-op, so teardown can be retried safely.
 */
internal class SqliteRunRepository(
    private val db: SqliteDatabase,
) : RunRepository {
    override suspend fun create(run: RunRecord) {
        val inserted =
            db.write {
                RunTable
                    .insertIgnore {
                        it[runId] = run.runId
                        it[runTag] = run.runTag
                        it[campaignHash] = run.campaignHash
                        it[campaignName] = run.campaignName
                        it[seed] = run.seed
                        it[target] = run.target
                        it[startedAt] = run.startedAt
                        it[endedAt] = run.endedAt
                        it[result] = run.result
                        it[repeatGroup] = run.repeatGroup
                        it[repeatIndex] = run.repeatIndex
                    }.insertedCount
            }
        require(inserted == 1) { "Run ${run.runId} already exists" }
    }

    override suspend fun finish(
        runId: RunId,
        result: RunResult,
        endedAt: Instant,
    ) {
        val updated =
            db.write {
                RunTable.update({ RunTable.runId eq runId }) {
                    it[RunTable.result] = result
                    it[RunTable.endedAt] = endedAt
                }
            }
        require(updated == 1) { "Cannot finish unknown run $runId" }
    }

    override suspend fun find(runId: RunId): RunRecord? =
        db.read {
            RunTable
                .selectAll()
                .where { RunTable.runId eq runId }
                .firstOrNull()
                ?.toRunRecord()
        }

    override suspend fun latest(): RunRecord? =
        db.read {
            RunTable
                .selectAll()
                .orderBy(RunTable.startedAt to SortOrder.DESC, RunTable.seq to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toRunRecord()
        }

    override suspend fun list(limit: Int): List<RunRecord> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return db.read {
            RunTable
                .selectAll()
                .orderBy(RunTable.startedAt to SortOrder.DESC, RunTable.seq to SortOrder.DESC)
                .limit(limit)
                .map { it.toRunRecord() }
        }
    }

    override suspend fun byRepeatGroup(group: String): List<RunRecord> =
        db.read {
            RunTable
                .selectAll()
                .where { RunTable.repeatGroup eq group }
                .orderBy(
                    RunTable.repeatIndex to SortOrder.ASC_NULLS_LAST,
                    RunTable.startedAt to SortOrder.ASC,
                    RunTable.seq to SortOrder.ASC,
                ).map { it.toRunRecord() }
        }

    override suspend fun addResource(resource: RunResource) {
        db.write {
            RunResourceTable.insertIgnore {
                it[runId] = resource.runId
                it[kind] = resource.kind
                it[externalId] = resource.externalId
                it[createdAt] = resource.createdAt
            }
        }
    }

    override suspend fun resources(runId: RunId): List<RunResource> =
        db.read {
            RunResourceTable
                .selectAll()
                .where { RunResourceTable.runId eq runId }
                .orderBy(RunResourceTable.createdAt to SortOrder.ASC, RunResourceTable.seq to SortOrder.ASC)
                .map { it.toRunResource() }
        }

    override suspend fun removeResource(
        runId: RunId,
        kind: String,
        externalId: String,
    ) {
        db.write {
            RunResourceTable.deleteWhere {
                (RunResourceTable.runId eq runId) and
                    (RunResourceTable.kind eq kind) and
                    (RunResourceTable.externalId eq externalId)
            }
        }
    }
}

private fun ResultRow.toRunRecord() =
    RunRecord(
        runId = this[RunTable.runId],
        runTag = this[RunTable.runTag],
        campaignHash = this[RunTable.campaignHash],
        campaignName = this[RunTable.campaignName],
        seed = this[RunTable.seed],
        target = this[RunTable.target],
        startedAt = this[RunTable.startedAt],
        endedAt = this[RunTable.endedAt],
        result = this[RunTable.result],
        repeatGroup = this[RunTable.repeatGroup],
        repeatIndex = this[RunTable.repeatIndex],
    )

private fun ResultRow.toRunResource() =
    RunResource(
        runId = this[RunResourceTable.runId],
        kind = this[RunResourceTable.kind],
        externalId = this[RunResourceTable.externalId],
        createdAt = this[RunResourceTable.createdAt],
    )
