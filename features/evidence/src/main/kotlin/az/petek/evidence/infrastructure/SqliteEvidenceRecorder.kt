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

package az.petek.evidence.infrastructure

import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Write side of the SQLite evidence store. Every write is one statement in its own transaction on the database's
 * single writer thread, so concurrent agents never interleave partial records.
 *
 * Records that carry an id (step, event, artifact, finding) and receipts (one per event and receiver) are upserted:
 * recording the same id again replaces the earlier values but keeps its original insertion position. Assertions
 * have no id and are appended. Usage is accumulated per (run, agent), see [usage].
 */
internal class SqliteEvidenceRecorder(
    private val db: SqliteDatabase,
) : EvidenceRecorder {
    override suspend fun step(record: StepRecord) {
        db.write {
            StepTable.upsert(StepTable.runId, StepTable.stepId) {
                it[runId] = record.runId
                it[stepId] = record.stepId
                it[agentId] = record.agentId
                it[scenarioStep] = record.scenarioStep
                it[kind] = record.kind
                it[action] = record.action
                it[llmReason] = record.llmReason
                it[startedAt] = record.startedAt
                it[endedAt] = record.endedAt
                it[durationMs] = record.durationMs
                it[status] = record.status
                it[detail] = record.detail
                it[correlationId] = record.correlationId
            }
        }
    }

    override suspend fun artifact(record: ArtifactRecord) {
        db.write {
            ArtifactTable.upsert(ArtifactTable.runId, ArtifactTable.artifactId) {
                it[runId] = record.runId
                it[artifactId] = record.artifactId
                it[stepId] = record.stepId
                it[type] = record.type
                it[relativePath] = record.relativePath
                it[sha256] = record.sha256
                it[sizeBytes] = record.sizeBytes
            }
        }
    }

    override suspend fun event(record: EventRecord) {
        db.write {
            EventTable.upsert(EventTable.runId, EventTable.eventId) {
                it[runId] = record.runId
                it[eventId] = record.eventId
                it[name] = record.name
                it[emitter] = record.emitter
                it[objectId] = record.objectId
                it[objectIdSource] = record.objectIdSource
                it[payloadJson] = record.payloadJson
                it[t0] = record.t0
            }
        }
    }

    override suspend fun receipt(record: EventReceipt) {
        db.write {
            ReceiptTable.upsert(ReceiptTable.runId, ReceiptTable.eventId, ReceiptTable.receiver) {
                it[runId] = record.runId
                it[eventId] = record.eventId
                it[receiver] = record.receiver
                it[received] = record.received
                it[t1] = record.t1
                it[latencyMs] = record.latencyMs
            }
        }
    }

    override suspend fun assertion(record: AssertionRecord) {
        db.write {
            AssertionTable.insert {
                it[runId] = record.runId
                it[stepId] = record.stepId
                it[agentId] = record.agentId
                it[scenarioStep] = record.scenarioStep
                it[type] = record.type
                it[evidenceSource] = record.source
                it[expected] = record.expected
                it[observed] = record.observed
                it[verdict] = record.verdict
                it[latencyMs] = record.latencyMs
                it[note] = record.note
                it[artifactIds] = record.artifactIds
            }
        }
    }

    override suspend fun finding(record: FindingRecord) {
        db.write {
            FindingTable.upsert(FindingTable.runId, FindingTable.findingId) {
                it[runId] = record.runId
                it[findingId] = record.findingId
                it[stepId] = record.stepId
                it[scenarioStep] = record.scenarioStep
                it[agentId] = record.agentId
                it[findingClass] = record.findingClass
                it[a] = record.a
                it[b] = record.b
                it[c] = record.c
                it[note] = record.note
                it[artifactIds] = record.artifactIds
                it[workspaceId] = record.workspaceId.value
                it[evidenceTier] = record.evidenceTier.name
            }
        }
    }

    /**
     * Adds [record] to the totals of its (run, agent) row in a single `INSERT … ON CONFLICT DO UPDATE`, so
     * concurrent reports of the same agent are never lost. Costs add up when known; a null cost means "unknown"
     * and only stays null while no call of that agent reported a cost.
     */
    override suspend fun usage(record: UsageRecord) {
        db.write {
            UsageTable.upsert(
                UsageTable.runId,
                UsageTable.agentId,
                onUpdate = {
                    it[UsageTable.inputTokens] = UsageTable.inputTokens + insertValue(UsageTable.inputTokens)
                    it[UsageTable.outputTokens] = UsageTable.outputTokens + insertValue(UsageTable.outputTokens)
                    it[UsageTable.cacheReadTokens] = UsageTable.cacheReadTokens + insertValue(UsageTable.cacheReadTokens)
                    it[UsageTable.calls] = UsageTable.calls + insertValue(UsageTable.calls)
                    // NULL + x is NULL in SQL, so COALESCE falls back to whichever side is known.
                    it[UsageTable.costUsd] =
                        coalesce(
                            UsageTable.costUsd + insertValue(UsageTable.costUsd),
                            UsageTable.costUsd,
                            insertValue(UsageTable.costUsd),
                        )
                },
            ) {
                it[runId] = record.runId
                it[agentId] = record.agentId
                it[inputTokens] = record.inputTokens
                it[outputTokens] = record.outputTokens
                it[cacheReadTokens] = record.cacheReadTokens
                it[costUsd] = record.costUsd
                it[calls] = record.calls
            }
        }
    }
}
