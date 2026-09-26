/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.evidence.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import az.petek.core.ids.WorkspaceId
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.EvidenceTier
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/*
 * SQLite schema of the evidence store (docs/PLAN.md "Sübut bazası və hesabat").
 *
 * - Every table has an auto-increment `seq`: it is the insertion order, the tie-breaker behind "ordered by time".
 * - Records with a natural id are unique per run (`run_id` + id), so re-recording the same id is idempotent.
 * - There are deliberately no foreign keys: evidence is an append-mostly log, and a record must never be lost
 *   because it arrived before its run row (e.g. a harness step recorded while the run is still being created).
 * - Every string column is `TEXT` (see EvidenceColumns.kt): no id, label or enum name is ever refused for its length.
 */

private fun Table.runIdColumn(): Column<RunId> = text("run_id").transform(::RunId, RunId::value)

private fun Table.agentIdColumn(name: String): Column<AgentId> = text(name).transform(::AgentId, AgentId::value)

private fun Table.stepIdColumn(): Column<StepId> = text("step_id").transform(::StepId, StepId::value)

internal object RunTable : Table("run") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val runTag = text("run_tag").transform(::RunTag, RunTag::value)
    val campaignHash = text("campaign_hash")
    val campaignName = text("campaign_name")
    val seed = long("seed")
    val target = text("target")
    val startedAt = instant("started_at")
    val endedAt = instant("ended_at").nullable()
    val result = enumName<RunResult>("result")
    val repeatGroup = text("repeat_group").nullable()
    val repeatIndex = integer("repeat_index").nullable()

    /** Added after the first release; older databases get it with the default (`SqliteDatabase.createMissing`). */
    val workspaceId = text("workspace_id").default(WorkspaceId.LOCAL.value)

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId)
        index(false, startedAt)
        index(false, repeatGroup, repeatIndex)
    }
}

internal object RunResourceTable : Table("run_resource") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val kind = text("kind")
    val externalId = text("external_id")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, kind, externalId)
    }
}

internal object StepTable : Table("step") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val stepId = stepIdColumn()
    val agentId = agentIdColumn("agent_id").nullable()
    val scenarioStep = text("scenario_step")
    val kind = enumName<StepKind>("kind")
    val action = text("action")
    val llmReason = text("llm_reason").nullable()
    val startedAt = instant("started_at")
    val endedAt = instant("ended_at")
    val durationMs = long("duration_ms")
    val status = enumName<StepStatus>("status")
    val detail = text("detail").nullable()
    val correlationId = text("correlation_id").transform(::CorrelationId, CorrelationId::value)

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, stepId)
        index(false, runId, startedAt)
    }
}

internal object EventTable : Table("event") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val eventId = text("event_id").transform(::EventId, EventId::value)
    val name = text("name")
    val emitter = agentIdColumn("emitter")
    val objectId = text("object_id").nullable()
    val objectIdSource = text("object_id_source").nullable()
    val payloadJson = text("payload_json")
    val t0 = instant("t0")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, eventId)
        index(false, runId, t0)
    }
}

internal object ReceiptTable : Table("receipt") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val eventId = text("event_id").transform(::EventId, EventId::value)
    val receiver = agentIdColumn("receiver")
    val received = bool("received")
    val t1 = instant("t1").nullable()
    val latencyMs = long("latency_ms").nullable()

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, eventId, receiver)
        index(false, runId, t1)
    }
}

internal object ArtifactTable : Table("artifact") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val artifactId = text("artifact_id").transform(::ArtifactId, ArtifactId::value)
    val stepId = stepIdColumn()
    val type = enumName<ArtifactType>("type")
    val relativePath = text("relative_path")
    val sha256 = text("sha256")
    val sizeBytes = long("size_bytes")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, artifactId)
    }
}

internal object AssertionTable : Table("assertion") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val stepId = stepIdColumn()
    val agentId = agentIdColumn("agent_id").nullable()
    val scenarioStep = text("scenario_step")
    val type = text("type")
    val evidenceSource = enumName<EvidenceSource>("source")
    val expected = text("expected")
    val observed = text("observed").nullable()
    val verdict = enumName<Verdict>("verdict")
    val latencyMs = long("latency_ms").nullable()
    val note = text("note").nullable()
    val artifactIds = artifactIds("artifact_ids")

    override val primaryKey = PrimaryKey(seq)

    init {
        index(false, runId)
    }
}

internal object FindingTable : Table("finding") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val findingId = text("finding_id").transform(::FindingId, FindingId::value)
    val stepId = stepIdColumn().nullable()
    val scenarioStep = text("scenario_step")
    val agentId = agentIdColumn("agent_id").nullable()
    val findingClass = enumName<FindingClass>("finding_class")
    val a = text("a").nullable()
    val b = text("b").nullable()
    val c = text("c").nullable()
    val note = text("note")
    val artifactIds = artifactIds("artifact_ids")
    val workspaceId = text("workspace_id").default(WorkspaceId.LOCAL.value)
    val evidenceTier = text("evidence_tier").default(EvidenceTier.UI_NETWORK.name)

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, findingId)
    }
}

/** One row per (run, agent); recording usage adds to the row's totals. */
internal object UsageTable : Table("usage") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val agentId = agentIdColumn("agent_id")
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val cacheReadTokens = long("cache_read_tokens")
    val costUsd = double("cost_usd").nullable()
    val calls = integer("calls")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, agentId)
    }
}

/** Every table of the evidence store, in creation order. */
internal val evidenceTables: Array<Table> =
    arrayOf(
        RunTable,
        RunResourceTable,
        StepTable,
        EventTable,
        ReceiptTable,
        ArtifactTable,
        AssertionTable,
        FindingTable,
        UsageTable,
    )
