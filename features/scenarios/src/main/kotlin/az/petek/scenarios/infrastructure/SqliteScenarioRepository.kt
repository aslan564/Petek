/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.infrastructure

import az.petek.core.sqlite.SqliteDatabase
import az.petek.scenarios.domain.ConcurrentScenarioChangeException
import az.petek.scenarios.domain.FrozenScenarioException
import az.petek.scenarios.domain.NewScenarioVersion
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.ScenarioVersionUpdate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The scenario catalog in SQLite (table `scenario_version`), built by the composition root from the shared
 * [SqliteDatabase]. Construction creates the table, its indexes and the immutability triggers (idempotent).
 *
 * Beyond the [ScenarioRepository] contract: [add] numbers versions inside the single writer's transaction, so
 * concurrent adds of one name get consecutive numbers; the database itself refuses a second APPROVED row per name and
 * any change to a FROZEN row or to content columns, so the rules hold even for writes that bypass this class.
 */
class SqliteScenarioRepository(
    private val db: SqliteDatabase,
) : ScenarioRepository {
    init {
        db.createMissing(ScenarioVersionTable)
        db.setUp { ScenarioSchema.statements.forEach { exec(it) } }
    }

    override suspend fun add(draft: NewScenarioVersion): ScenarioVersion =
        db.write {
            require(findRow(draft.id) == null) { "Scenario version ${draft.id} already exists" }
            val highest = ScenarioVersionTable.version.max()
            val next =
                (
                    ScenarioVersionTable
                        .select(highest)
                        .where { ScenarioVersionTable.name eq draft.name }
                        .single()[highest] ?: 0
                ) + 1
            val stored = draft.toVersion(next)
            ScenarioVersionTable.insert {
                it[id] = stored.id
                it[name] = stored.name
                it[version] = stored.version
                it[yaml] = stored.yaml
                it[sha256] = stored.sha256
                it[status] = stored.status
                it[origin] = stored.source
                it[parentId] = stored.parentId
                it[note] = stored.note
                it[createdAt] = stored.createdAt
                it[approvedAt] = stored.approvedAt
                it[frozenAt] = stored.frozenAt
                it[supersededBy] = stored.supersededBy
            }
            stored
        }

    override suspend fun find(id: ScenarioVersionId): ScenarioVersion? = db.read { findRow(id) }

    override suspend fun history(name: String): List<ScenarioVersion> =
        db.read {
            ScenarioVersionTable
                .selectAll()
                .where { ScenarioVersionTable.name eq name }
                .orderBy(ScenarioVersionTable.version to SortOrder.ASC)
                .map { it.toVersion() }
        }

    override suspend fun all(): List<ScenarioVersion> =
        db.read {
            ScenarioVersionTable
                .selectAll()
                .orderBy(ScenarioVersionTable.name to SortOrder.ASC, ScenarioVersionTable.version to SortOrder.ASC)
                .map { it.toVersion() }
        }

    override suspend fun withHash(sha256: String): List<ScenarioVersion> =
        db.read {
            ScenarioVersionTable
                .selectAll()
                .where { ScenarioVersionTable.sha256 eq sha256 }
                .orderBy(ScenarioVersionTable.createdAt to SortOrder.ASC, ScenarioVersionTable.version to SortOrder.ASC)
                .map { it.toVersion() }
        }

    override suspend fun update(updates: List<ScenarioVersionUpdate>) {
        if (updates.isEmpty()) return
        db.write {
            updates.forEach { update ->
                val stored = findRow(update.id) ?: throw ScenarioNotFoundException(update.id)
                if (stored.status == ScenarioStatus.FROZEN) throw FrozenScenarioException(update.id)
                if (stored.status != update.before.status) throw ConcurrentScenarioChangeException(update.id)
                val after = update.after
                if (after.status == ScenarioStatus.APPROVED && approvedOtherThan(after)) throw ConcurrentScenarioChangeException(update.id)
                val changed =
                    ScenarioVersionTable.update({
                        (ScenarioVersionTable.id eq update.id) and (ScenarioVersionTable.status eq update.before.status)
                    }) {
                        it[status] = after.status
                        it[approvedAt] = after.approvedAt
                        it[frozenAt] = after.frozenAt
                        it[supersededBy] = after.supersededBy
                    }
                if (changed != 1) throw ConcurrentScenarioChangeException(update.id)
            }
        }
    }

    /** Another version of [version]'s name is APPROVED: someone approved it after this batch was computed. */
    private fun JdbcTransaction.approvedOtherThan(version: ScenarioVersion): Boolean =
        !ScenarioVersionTable
            .selectAll()
            .where {
                (ScenarioVersionTable.name eq version.name) and
                    (ScenarioVersionTable.status eq ScenarioStatus.APPROVED) and
                    (ScenarioVersionTable.id neq version.id)
            }.empty()

    private fun JdbcTransaction.findRow(id: ScenarioVersionId): ScenarioVersion? =
        ScenarioVersionTable
            .selectAll()
            .where { ScenarioVersionTable.id eq id }
            .singleOrNull()
            ?.toVersion()

    private fun ResultRow.toVersion(): ScenarioVersion =
        ScenarioVersion(
            id = this[ScenarioVersionTable.id],
            name = this[ScenarioVersionTable.name],
            version = this[ScenarioVersionTable.version],
            yaml = this[ScenarioVersionTable.yaml],
            status = this[ScenarioVersionTable.status],
            source = this[ScenarioVersionTable.origin],
            parentId = this[ScenarioVersionTable.parentId],
            note = this[ScenarioVersionTable.note],
            createdAt = this[ScenarioVersionTable.createdAt],
            approvedAt = this[ScenarioVersionTable.approvedAt],
            frozenAt = this[ScenarioVersionTable.frozenAt],
            supersededBy = this[ScenarioVersionTable.supersededBy],
        )
}
