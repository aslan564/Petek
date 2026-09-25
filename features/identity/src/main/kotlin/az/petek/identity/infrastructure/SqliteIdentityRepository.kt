/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.identity.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.core.sqlite.SqliteDatabase
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityConflictException
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRepository
import az.petek.identity.domain.IdentityStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.SQLException

/**
 * [IdentityRepository] on the shared SQLite database (table `identity`, see [IdentityTable]); the composition root
 * creates one per process. Writes go through [SqliteDatabase.write], reads through [SqliteDatabase.read].
 *
 * [replaceAll] swaps a run's identities in one write transaction, so `petek plan` can be repeated and a failed
 * replacement leaves the previous registry untouched. An e-mail already used by another run, or a plan that
 * repeats an agent id, e-mail or display name, is an [IdentityConflictException] naming the offending values;
 * conflicts are detected before inserting so no SQL error (which could carry row values) is raised for them.
 *
 * [updateStatus] and [updateStorageState] throw [NoSuchElementException] for an agent the run does not have:
 * the orchestrator only updates identities it planned, so a miss is a bug that must not go unnoticed.
 * A status update stores [updateStatus]'s reason as given, so a status without a reason clears the old one.
 */
class SqliteIdentityRepository(
    private val db: SqliteDatabase,
) : IdentityRepository {
    init {
        db.createMissing(IdentityTable)
    }

    override suspend fun replaceAll(
        runId: RunId,
        plan: IdentityPlan,
    ) {
        requireUniqueWithinPlan(runId, plan)
        val emails = plan.identities.map { it.email }
        try {
            db.write {
                IdentityTable.deleteWhere { IdentityTable.runId eq runId.value }
                val taken = emailsOfOtherRuns(runId, emails)
                if (taken.isNotEmpty()) throw emailConflict(runId, taken)
                IdentityTable.batchInsert(plan.identities, shouldReturnGeneratedValues = false) { identity ->
                    this[IdentityTable.runId] = runId.value
                    this[IdentityTable.agentId] = identity.agentId.value
                    this[IdentityTable.displayName] = identity.displayName
                    this[IdentityTable.email] = identity.email
                    this[IdentityTable.password] = identity.password.reveal()
                    this[IdentityTable.phone] = identity.phone
                    this[IdentityTable.role] = identity.role.key
                    this[IdentityTable.department] = identity.department
                    this[IdentityTable.registration] = identity.registration.key
                    this[IdentityTable.status] = identity.status.key
                    this[IdentityTable.statusReason] = null
                    this[IdentityTable.storageStatePath] = identity.storageStatePath
                }
            }
        } catch (e: SQLException) {
            // Only reachable when another process inserted the same e-mail between our check and our insert.
            if (!e.isUniqueViolation()) throw e
            throw emailConflict(runId, db.read { emailsOfOtherRuns(runId, emails) })
        }
    }

    override suspend fun findByRun(runId: RunId): List<Identity> =
        db
            .read {
                IdentityTable
                    .selectAll()
                    .where { IdentityTable.runId eq runId.value }
                    .map { it.toIdentity() }
            }.sortedBy { it.agentId }

    override suspend fun updateStatus(
        runId: RunId,
        agentId: AgentId,
        status: IdentityStatus,
        reason: String?,
    ) {
        val updated =
            db.write {
                IdentityTable.update({ row(runId, agentId) }) {
                    it[IdentityTable.status] = status.key
                    it[IdentityTable.statusReason] = reason
                }
            }
        requireFound(updated, runId, agentId)
    }

    override suspend fun updateStorageState(
        runId: RunId,
        agentId: AgentId,
        path: String,
    ) {
        val updated =
            db.write {
                IdentityTable.update({ row(runId, agentId) }) { it[IdentityTable.storageStatePath] = path }
            }
        requireFound(updated, runId, agentId)
    }

    private fun requireUniqueWithinPlan(
        runId: RunId,
        plan: IdentityPlan,
    ) {
        val problems =
            duplicates("agent id", plan.identities.map { it.agentId.value }) +
                duplicates("e-mail", plan.identities.map { it.email.lowercase() }) +
                duplicates("display name", plan.identities.map { it.displayName })
        if (problems.isNotEmpty()) {
            throw IdentityConflictException("Cannot store identities for run $runId: " + problems.joinToString("; "))
        }
    }

    private fun duplicates(
        label: String,
        values: List<String>,
    ): List<String> =
        values
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map { "duplicate $label $it" }

    /** Must run inside a transaction; SQLite's NOCASE collation of the column makes the match case-insensitive. */
    private fun emailsOfOtherRuns(
        runId: RunId,
        emails: List<String>,
    ): List<String> =
        emails.chunked(EMAIL_LOOKUP_CHUNK).flatMap { chunk ->
            IdentityTable
                .select(IdentityTable.email)
                .where { (IdentityTable.email inList chunk) and (IdentityTable.runId neq runId.value) }
                .map { it[IdentityTable.email] }
        }

    private fun emailConflict(
        runId: RunId,
        emails: List<String>,
    ) = IdentityConflictException(
        "Cannot store identities for run $runId: e-mail already used by another run: " +
            emails.ifEmpty { listOf("(unknown, the conflicting row was removed meanwhile)") }.joinToString(", "),
    )

    private fun row(
        runId: RunId,
        agentId: AgentId,
    ): Op<Boolean> = (IdentityTable.runId eq runId.value) and (IdentityTable.agentId eq agentId.value)

    private fun requireFound(
        updated: Int,
        runId: RunId,
        agentId: AgentId,
    ) {
        if (updated == 0) throw NoSuchElementException("No identity $agentId in run $runId")
    }

    private fun ResultRow.toIdentity(): Identity {
        val agentId = AgentId(this[IdentityTable.agentId])
        return Identity(
            agentId = agentId,
            displayName = this[IdentityTable.displayName],
            email = this[IdentityTable.email],
            password = Secret(this[IdentityTable.password]),
            phone = this[IdentityTable.phone],
            role = decode(this[IdentityTable.role], agentId) { Role.fromKey(it) },
            department = this[IdentityTable.department],
            registration = decode(this[IdentityTable.registration], agentId) { RegistrationMode.fromKey(it) },
            status = decode(this[IdentityTable.status], agentId) { key -> IdentityStatus.entries.find { it.key == key } },
            storageStatePath = this[IdentityTable.storageStatePath],
        )
    }

    private fun <T : Any> decode(
        stored: String,
        agentId: AgentId,
        parse: (String) -> T?,
    ): T = parse(stored) ?: throw IllegalStateException("Unknown value '$stored' stored for identity $agentId")

    private fun SQLException.isUniqueViolation(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.any { it.message?.contains(UNIQUE_VIOLATION) == true }

    private companion object {
        const val UNIQUE_VIOLATION = "UNIQUE constraint failed"

        /** Well below SQLite's limit on bound parameters per statement. */
        const val EMAIL_LOOKUP_CHUNK = 500

        /** Stored form of a status, matching docs/PLAN.md (`planned`, `registered`, `active`, `failed`). */
        val IdentityStatus.key: String get() = name.lowercase()
    }
}
