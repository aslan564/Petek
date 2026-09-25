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
import az.petek.core.ids.RunId
import az.petek.scenarios.domain.ProposalStatus
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.SurpriseId
import az.petek.scenarios.domain.SurpriseKind
import az.petek.scenarios.domain.TriageCategory
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/*
 * SQLite schema of the scenario catalog and triage results (tables `scenario_version`, `triage_surprise`,
 * `triage_verdict`, `triage_failure`). Encodings follow the evidence store so the file reads the same with plain SQL
 * tools: every string is unbounded TEXT, enums are stored by name, instants are ISO-8601 UTC text with nine
 * fractional digits (text order is time order), id lists and edits are JSON.
 *
 * Invariants the database enforces itself, whatever code writes to it (see [ScenarioSchema]):
 * - `(name, version)` is unique and at most one row per name is APPROVED;
 * - a FROZEN row can be neither updated nor deleted, and the content columns of any row never change.
 */

internal object InstantText {
    private val format: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter(Locale.ROOT)

    fun encode(instant: Instant): String = format.format(instant)

    fun decode(text: String): Instant = Instant.parse(text)
}

internal fun Table.instant(name: String): Column<Instant> = text(name).transform(InstantText::decode, InstantText::encode)

internal inline fun <reified E : Enum<E>> Table.enumName(name: String): Column<E> =
    text(name).transform({
        enumValueOf<E>(it)
    }, { it.name })

internal fun Table.versionId(name: String): Column<ScenarioVersionId> = text(name).transform(::ScenarioVersionId, ScenarioVersionId::value)

internal fun Table.runIdColumn(): Column<RunId> = text("run_id").transform(::RunId, RunId::value)

internal fun Table.surpriseIdColumn(): Column<SurpriseId> = text("surprise_id").transform(::SurpriseId, SurpriseId::value)

internal object ScenarioVersionTable : Table("scenario_version") {
    val id = versionId("id")
    val name = text("name")
    val version = integer("version")
    val yaml = text("yaml")
    val sha256 = text("sha256")
    val status = enumName<ScenarioStatus>("status")
    val origin = enumName<ScenarioSource>("source")
    val parentId = versionId("parent_id").nullable()
    val note = text("note")
    val createdAt = instant("created_at")
    val approvedAt = instant("approved_at").nullable()
    val frozenAt = instant("frozen_at").nullable()
    val supersededBy = versionId("superseded_by").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(name, version)
        index(false, sha256)
    }
}

internal object SurpriseTable : Table("triage_surprise") {
    val seq = long("seq").autoIncrement()
    val id = text("id").transform(::SurpriseId, SurpriseId::value)
    val runId = runIdColumn()
    val scenarioStep = text("scenario_step")
    val agentId = text("agent_id").transform(::AgentId, AgentId::value).nullable()
    val kind = enumName<SurpriseKind>("kind")
    val text = text("text")
    val stepIds = text("step_ids")
    val artifactIds = text("artifact_ids")
    val findingIds = text("finding_ids")
    val facts = text("facts")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(id)
        index(false, runId)
    }
}

internal object VerdictTable : Table("triage_verdict") {
    val seq = long("seq").autoIncrement()
    val surpriseId = surpriseIdColumn()
    val runId = runIdColumn()
    val scenarioVersionId = versionId("scenario_version_id")
    val category = enumName<TriageCategory>("category")
    val rationale = text("rationale")
    val confidence = double("confidence")
    val basedOn = text("based_on")
    val model = text("model")
    val decidedAt = instant("decided_at")
    val proposalSummary = text("proposal_summary").nullable()
    val proposalEdits = text("proposal_edits").nullable()
    val proposalStatus = enumName<ProposalStatus>("proposal_status").nullable()
    val proposalRejection = text("proposal_rejection").nullable()
    val draftId = versionId("draft_id").nullable()

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(surpriseId)
        index(false, runId)
        index(false, draftId)
    }
}

internal object FailureTable : Table("triage_failure") {
    val seq = long("seq").autoIncrement()
    val surpriseId = surpriseIdColumn()
    val runId = runIdColumn()
    val reason = text("reason")
    val failedAt = instant("failed_at")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(surpriseId)
        index(false, runId)
    }
}

/** Statements beyond what Exposed declares: the partial unique index and the immutability triggers. */
internal object ScenarioSchema {
    const val FROZEN_MESSAGE = "scenario version is FROZEN and immutable"
    const val CONTENT_MESSAGE = "scenario version content is immutable"

    val statements: List<String> =
        listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS scenario_version_one_approved ON scenario_version(name) WHERE status = 'APPROVED'",
            """
            CREATE TRIGGER IF NOT EXISTS scenario_version_frozen_update BEFORE UPDATE ON scenario_version
            WHEN OLD.status = 'FROZEN'
            BEGIN SELECT RAISE(ABORT, '$FROZEN_MESSAGE'); END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS scenario_version_frozen_delete BEFORE DELETE ON scenario_version
            WHEN OLD.status = 'FROZEN'
            BEGIN SELECT RAISE(ABORT, '$FROZEN_MESSAGE'); END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS scenario_version_content_update
            BEFORE UPDATE OF id, name, version, yaml, sha256, source, parent_id, note, created_at ON scenario_version
            BEGIN SELECT RAISE(ABORT, '$CONTENT_MESSAGE'); END
            """.trimIndent(),
        )
}
