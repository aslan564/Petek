/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.evidence.testing

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResource
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Thread-safe in-memory evidence store for tests (recorder + query + runs). */
class InMemoryEvidence :
    EvidenceRecorder,
    EvidenceQuery,
    RunRepository {
    val stepList = CopyOnWriteArrayList<StepRecord>()
    val artifactList = CopyOnWriteArrayList<ArtifactRecord>()
    val eventList = CopyOnWriteArrayList<EventRecord>()
    val receiptList = CopyOnWriteArrayList<EventReceipt>()
    val assertionList = CopyOnWriteArrayList<AssertionRecord>()
    val findingList = CopyOnWriteArrayList<FindingRecord>()
    val usageList = CopyOnWriteArrayList<UsageRecord>()
    val runList = CopyOnWriteArrayList<RunRecord>()
    val resourceList = CopyOnWriteArrayList<RunResource>()

    override suspend fun step(record: StepRecord) {
        stepList += record
    }

    override suspend fun artifact(record: ArtifactRecord) {
        artifactList += record
    }

    override suspend fun event(record: EventRecord) {
        eventList += record
    }

    override suspend fun receipt(record: EventReceipt) {
        receiptList += record
    }

    override suspend fun assertion(record: AssertionRecord) {
        assertionList += record
    }

    override suspend fun finding(record: FindingRecord) {
        findingList += record
    }

    override suspend fun usage(record: UsageRecord) {
        usageList += record
    }

    override suspend fun steps(runId: RunId) = stepList.filter { it.runId == runId }

    override suspend fun artifacts(runId: RunId) = artifactList.filter { it.runId == runId }

    override suspend fun events(runId: RunId) = eventList.filter { it.runId == runId }

    override suspend fun receipts(runId: RunId) = receiptList.filter { it.runId == runId }

    override suspend fun assertions(runId: RunId) = assertionList.filter { it.runId == runId }

    override suspend fun findings(runId: RunId) = findingList.filter { it.runId == runId }

    override suspend fun usage(runId: RunId) = usageList.filter { it.runId == runId }

    override suspend fun create(run: RunRecord) {
        runList += run
    }

    override suspend fun finish(
        runId: RunId,
        result: RunResult,
        endedAt: Instant,
    ) {
        val i = runList.indexOfFirst { it.runId == runId }
        if (i >= 0) runList[i] = runList[i].copy(result = result, endedAt = endedAt)
    }

    override suspend fun find(runId: RunId) = runList.firstOrNull { it.runId == runId }

    override suspend fun latest() = runList.maxByOrNull { it.startedAt }

    /** Newest first; equal start times keep the later-created run first, like the SQLite store. */
    override suspend fun list(limit: Int): List<RunRecord> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return runList
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<RunRecord>> { it.value.startedAt }.thenByDescending { it.index })
            .take(limit)
            .map { it.value }
    }

    override suspend fun byRepeatGroup(group: String) = runList.filter { it.repeatGroup == group }.sortedBy { it.repeatIndex }

    override suspend fun addResource(resource: RunResource) {
        resourceList += resource
    }

    override suspend fun resources(runId: RunId) = resourceList.filter { it.runId == runId }

    override suspend fun removeResource(
        runId: RunId,
        kind: String,
        externalId: String,
    ) {
        resourceList.removeIf { it.runId == runId && it.kind == kind && it.externalId == externalId }
    }
}

/** Keeps artifact bytes in memory; paths are virtual. */
class InMemoryArtifactStore(
    private val root: Path = Path.of("build", "test-evidence"),
) : ArtifactStore {
    private val counter = AtomicInteger()
    val contents = java.util.concurrent.ConcurrentHashMap<ArtifactId, ByteArray>()

    override suspend fun write(
        runId: RunId,
        stepId: StepId,
        owner: String,
        type: ArtifactType,
        bytes: ByteArray,
    ): ArtifactRecord {
        val n = counter.incrementAndGet()
        val id = ArtifactId("art_$n")
        contents[id] = bytes
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ArtifactRecord(
            id,
            runId,
            stepId,
            type,
            "$runId/$owner/${n.toString().padStart(4, '0')}-${type.name.lowercase()}.${type.extension}",
            sha,
            bytes.size.toLong(),
        )
    }

    override fun resolve(record: ArtifactRecord): Path = root.resolve(record.relativePath)

    override fun runDirectory(runId: RunId): Path = root.resolve(runId.value)
}
