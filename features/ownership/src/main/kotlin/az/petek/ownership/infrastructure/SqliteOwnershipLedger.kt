/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.infrastructure

import az.petek.core.sqlite.SqliteDatabase
import az.petek.ownership.domain.OwnershipLedger
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** Hosts whose ownership was proved: one row per host, the latest proof wins. */
internal object SiteOwnershipTable : Table("site_ownership") {
    val host = varchar("host", MAX_HOST)
    val method = varchar("method", MAX_METHOD)

    /** ISO-8601 UTC, e.g. `2026-09-26T10:00:00Z`. */
    val verifiedAt = varchar("verified_at", MAX_INSTANT)

    override val primaryKey = PrimaryKey(host)

    private const val MAX_HOST = 255
    private const val MAX_METHOD = 16
    private const val MAX_INSTANT = 40
}

/** [OwnershipLedger] on the shared SQLite database; the composition root creates one per process. */
class SqliteOwnershipLedger(
    private val db: SqliteDatabase,
) : OwnershipLedger {
    init {
        db.createMissing(SiteOwnershipTable)
    }

    override suspend fun find(host: String): OwnershipRecord? =
        db.read {
            SiteOwnershipTable
                .selectAll()
                .where { SiteOwnershipTable.host eq host }
                .singleOrNull()
                ?.toRecord()
        }

    override suspend fun save(record: OwnershipRecord) {
        db.write {
            SiteOwnershipTable.upsert(SiteOwnershipTable.host) {
                it[host] = record.host
                it[method] = record.method.key
                it[verifiedAt] = record.verifiedAt.toString()
            }
        }
    }

    private fun ResultRow.toRecord() =
        OwnershipRecord(
            host = this[SiteOwnershipTable.host],
            method = OwnershipMethod.ofKey(this[SiteOwnershipTable.method]),
            verifiedAt = Instant.parse(this[SiteOwnershipTable.verifiedAt]),
        )
}
