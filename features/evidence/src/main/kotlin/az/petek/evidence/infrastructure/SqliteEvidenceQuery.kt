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
import az.petek.core.ids.WorkspaceId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.EvidenceTier
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Read side of the SQLite evidence store. Every list is ordered by the record's own time and then by insertion
 * order: steps by `startedAt`, events by `t0`, receipts by `t1` (receipts never received come last). Artifacts,
 * assertions, findings and usage carry no timestamp, so they come in the order they were first recorded.
 */
internal class SqliteEvidenceQuery(
    private val db: SqliteDatabase,
) : EvidenceQuery {
    override suspend fun steps(runId: RunId): List<StepRecord> =
        db.read {
            StepTable
                .selectAll()
                .where { StepTable.runId eq runId }
                .orderBy(StepTable.startedAt to SortOrder.ASC, StepTable.seq to SortOrder.ASC)
                .map { it.toStepRecord() }
        }

    override suspend fun artifacts(runId: RunId): List<ArtifactRecord> =
        db.read {
            ArtifactTable
                .selectAll()
                .where { ArtifactTable.runId eq runId }
                .orderBy(ArtifactTable.seq to SortOrder.ASC)
                .map { it.toArtifactRecord() }
        }

    override suspend fun events(runId: RunId): List<EventRecord> =
        db.read {
            EventTable
                .selectAll()
                .where { EventTable.runId eq runId }
                .orderBy(EventTable.t0 to SortOrder.ASC, EventTable.seq to SortOrder.ASC)
                .map { it.toEventRecord() }
        }

    override suspend fun receipts(runId: RunId): List<EventReceipt> =
        db.read {
            ReceiptTable
                .selectAll()
                .where { ReceiptTable.runId eq runId }
                .orderBy(ReceiptTable.t1 to SortOrder.ASC_NULLS_LAST, ReceiptTable.seq to SortOrder.ASC)
                .map { it.toEventReceipt() }
        }

    override suspend fun assertions(runId: RunId): List<AssertionRecord> =
        db.read {
            AssertionTable
                .selectAll()
                .where { AssertionTable.runId eq runId }
                .orderBy(AssertionTable.seq to SortOrder.ASC)
                .map { it.toAssertionRecord() }
        }

    override suspend fun findings(runId: RunId): List<FindingRecord> =
        db.read {
            FindingTable
                .selectAll()
                .where { FindingTable.runId eq runId }
                .orderBy(FindingTable.seq to SortOrder.ASC)
                .map { it.toFindingRecord() }
        }

    override suspend fun usage(runId: RunId): List<UsageRecord> =
        db.read {
            UsageTable
                .selectAll()
                .where { UsageTable.runId eq runId }
                .orderBy(UsageTable.seq to SortOrder.ASC)
                .map { it.toUsageRecord() }
        }
}

private fun ResultRow.toStepRecord() =
    StepRecord(
        stepId = this[StepTable.stepId],
        runId = this[StepTable.runId],
        agentId = this[StepTable.agentId],
        scenarioStep = this[StepTable.scenarioStep],
        kind = this[StepTable.kind],
        action = this[StepTable.action],
        llmReason = this[StepTable.llmReason],
        startedAt = this[StepTable.startedAt],
        endedAt = this[StepTable.endedAt],
        durationMs = this[StepTable.durationMs],
        status = this[StepTable.status],
        detail = this[StepTable.detail],
        correlationId = this[StepTable.correlationId],
    )

private fun ResultRow.toArtifactRecord() =
    ArtifactRecord(
        artifactId = this[ArtifactTable.artifactId],
        runId = this[ArtifactTable.runId],
        stepId = this[ArtifactTable.stepId],
        type = this[ArtifactTable.type],
        relativePath = this[ArtifactTable.relativePath],
        sha256 = this[ArtifactTable.sha256],
        sizeBytes = this[ArtifactTable.sizeBytes],
    )

private fun ResultRow.toEventRecord() =
    EventRecord(
        eventId = this[EventTable.eventId],
        runId = this[EventTable.runId],
        name = this[EventTable.name],
        emitter = this[EventTable.emitter],
        objectId = this[EventTable.objectId],
        objectIdSource = this[EventTable.objectIdSource],
        payloadJson = this[EventTable.payloadJson],
        t0 = this[EventTable.t0],
    )

private fun ResultRow.toEventReceipt() =
    EventReceipt(
        eventId = this[ReceiptTable.eventId],
        runId = this[ReceiptTable.runId],
        receiver = this[ReceiptTable.receiver],
        received = this[ReceiptTable.received],
        t1 = this[ReceiptTable.t1],
        latencyMs = this[ReceiptTable.latencyMs],
    )

private fun ResultRow.toAssertionRecord() =
    AssertionRecord(
        stepId = this[AssertionTable.stepId],
        runId = this[AssertionTable.runId],
        agentId = this[AssertionTable.agentId],
        scenarioStep = this[AssertionTable.scenarioStep],
        type = this[AssertionTable.type],
        source = this[AssertionTable.evidenceSource],
        expected = this[AssertionTable.expected],
        observed = this[AssertionTable.observed],
        verdict = this[AssertionTable.verdict],
        latencyMs = this[AssertionTable.latencyMs],
        note = this[AssertionTable.note],
        artifactIds = this[AssertionTable.artifactIds],
    )

private fun ResultRow.toFindingRecord() =
    FindingRecord(
        findingId = this[FindingTable.findingId],
        runId = this[FindingTable.runId],
        stepId = this[FindingTable.stepId],
        scenarioStep = this[FindingTable.scenarioStep],
        agentId = this[FindingTable.agentId],
        findingClass = this[FindingTable.findingClass],
        a = this[FindingTable.a],
        b = this[FindingTable.b],
        c = this[FindingTable.c],
        note = this[FindingTable.note],
        artifactIds = this[FindingTable.artifactIds],
        workspaceId = WorkspaceId(this[FindingTable.workspaceId]),
        evidenceTier = EvidenceTier.entries.firstOrNull { it.name == this[FindingTable.evidenceTier] } ?: EvidenceTier.UI_NETWORK,
    )

private fun ResultRow.toUsageRecord() =
    UsageRecord(
        runId = this[UsageTable.runId],
        agentId = this[UsageTable.agentId],
        inputTokens = this[UsageTable.inputTokens],
        outputTokens = this[UsageTable.outputTokens],
        cacheReadTokens = this[UsageTable.cacheReadTokens],
        costUsd = this[UsageTable.costUsd],
        calls = this[UsageTable.calls],
    )
