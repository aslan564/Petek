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
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
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
 * replacement leaves the previous registry untouched. A plan that repeats an agent id, e-mail or display name, or a
 * generated tester whose e-mail another run already stored, is an [IdentityConflictException] naming the offending
 * values; conflicts are detected before inserting so no SQL error (which could carry row values) is raised for them.
 * A `login` tester ([Identity.ownAccount]) signs in with the owner's account, so its e-mail is stored again by every run
 * that uses it, and it is stored without the owner's password ([Identity.asStored]).
 *
 * Opening the repository moves a database written by an earlier release over ([moveOver]): the old index that made
 * every e-mail unique gives way to [IdentityTable.GENERATED_EMAIL_INDEX], the owner's passwords the `login` rows held
 * are cleared, and the file is rebuilt ([SqliteDatabase.vacuum]) so that no copy of them is left in its free space.
 * Every row is kept, and opening it again changes nothing.
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
        if (db.setUp { moveOver() }) {
            // The old index goes last: a rebuild that fails leaves it, so the next start rebuilds the file again.
            db.vacuum()
            db.setUp { exec("DROP INDEX IF EXISTS \"${IdentityTable.LEGACY_EMAIL_INDEX}\"") }
        }
    }

    override suspend fun replaceAll(
        runId: RunId,
        plan: IdentityPlan,
    ) {
        requireUniqueWithinPlan(runId, plan)
        // Only the e-mails Pətək generated must be new to the database; the owner's accounts repeat in every run.
        val emails = plan.identities.filterNot { it.ownAccount }.map { it.email }
        try {
            db.write {
                IdentityTable.deleteWhere { IdentityTable.runId eq runId.value }
                val taken = emailsOfOtherRuns(runId, emails)
                if (taken.isNotEmpty()) throw emailConflict(runId, taken)
                IdentityTable.batchInsert(plan.identities.map { it.asStored() }, shouldReturnGeneratedValues = false) { identity ->
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
                    this[IdentityTable.workspaceId] = plan.workspaceId.value
                }
            }
        } catch (e: SQLException) {
            // Only reachable when another process inserted the same generated e-mail between our check and our insert.
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

    /** The same check the generator makes ([IdentityPlan.duplicates]), for any plan handed to [replaceAll]. */
    private fun requireUniqueWithinPlan(
        runId: RunId,
        plan: IdentityPlan,
    ) {
        val problems = plan.duplicates()
        if (problems.isNotEmpty()) {
            throw IdentityConflictException("Cannot store identities for run $runId: " + problems.joinToString("; "))
        }
    }

    /**
     * Which of [emails] another run stored, for any of its testers: a generated tester's e-mail is never one an earlier
     * run used, not even for an owner's account. Must run inside a transaction; SQLite's NOCASE collation of the column
     * makes the match case-insensitive.
     */
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

    /**
     * Moves a database an earlier release created over (idempotent, in the start-up transaction): creates
     * [IdentityTable.GENERATED_EMAIL_INDEX] as a new database gets it (that index's own statement, so both stay the
     * same) and clears the owner's passwords the `login` rows of earlier runs held. No row is removed; the old index
     * allowed no repeated e-mail, so the new one, which covers fewer rows, always builds. Returns whether the database
     * still has [IdentityTable.LEGACY_EMAIL_INDEX], the mark of a database an earlier release wrote: its file may hold
     * the owner's passwords in free space, left there when earlier releases updated a `login` row (its status, its
     * storage state) or replaced a run's registry, also of rows no longer there, so it is rebuilt before the index is
     * dropped. A database a release with the partial index created never had them.
     */
    private fun JdbcTransaction.moveOver(): Boolean {
        val indexes = mutableSetOf<String>()
        exec("PRAGMA index_list(\"${IdentityTable.tableName}\")") { rows ->
            while (rows.next()) indexes += rows.getString("name").lowercase()
        }
        if (IdentityTable.GENERATED_EMAIL_INDEX !in indexes) {
            IdentityTable.indices
                .single { it.indexName == IdentityTable.GENERATED_EMAIL_INDEX }
                .createStatement()
                .forEach { exec(it) }
        }
        val ownPasswordKept =
            (IdentityTable.registration eq RegistrationMode.LOGIN.key) and (IdentityTable.password neq Identity.NOT_STORED.reveal())
        IdentityTable.update({ ownPasswordKept }) { it[IdentityTable.password] = Identity.NOT_STORED.reveal() }
        return IdentityTable.LEGACY_EMAIL_INDEX in indexes
    }

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
