package az.petek.evidence.infrastructure

import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.RunRepository

/**
 * The SQLite evidence store: one object that is the [EvidenceRecorder], the [EvidenceQuery] and the
 * [RunRepository] of a process, built by the composition root (`app`) from the shared [SqliteDatabase].
 *
 * Construction creates the missing evidence tables (`run`, `run_resource`, `step`, `event`, `receipt`,
 * `artifact`, `assertion`, `finding`, `usage`) and their indexes; it is idempotent, so an existing evidence
 * database is reused as is. Writes go through [SqliteDatabase.write] (one serialized writer, safe for many
 * concurrent agents), reads through [SqliteDatabase.read].
 *
 * Behaviour beyond the port signatures:
 * - Query results are ordered by time, then by insertion order (receipts never received come last; records
 *   without a timestamp come in recording order). [latest] is the run with the most recent start and
 *   [byRepeatGroup] is ordered by repeat index.
 * - Re-recording a step, event, artifact or finding with the same id (or a receipt for the same event and
 *   receiver) replaces it in place, so retries are idempotent. Assertions are always appended.
 * - [usage] adds to the totals of the agent's row; [EvidenceQuery.usage] returns one record per agent.
 * - [create] throws [IllegalArgumentException] for an existing run id, [finish] for an unknown one. Adding a
 *   resource twice keeps the first registration; removing a missing one does nothing.
 *
 * Storage rules, relevant when reading the file with other tools: instants are ISO-8601 UTC text with nine
 * fractional digits (lossless, and text order is time order), enums are stored by name, and artifact id lists
 * are JSON arrays of strings.
 */
class SqliteEvidenceStore(
    db: SqliteDatabase,
) : EvidenceRecorder by SqliteEvidenceRecorder(db),
    EvidenceQuery by SqliteEvidenceQuery(db),
    RunRepository by SqliteRunRepository(db) {
    init {
        db.createMissing(*evidenceTables)
    }
}
