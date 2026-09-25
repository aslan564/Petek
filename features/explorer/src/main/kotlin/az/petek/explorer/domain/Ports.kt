/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

import az.petek.core.ids.ArtifactId
import az.petek.evidence.domain.ArtifactRecord
import java.net.URI
import java.time.Instant

/** Lifecycle records of explorations. */
interface ExplorationRecords {
    /** Stores a new exploration; an id that already exists is refused with [IllegalArgumentException]. */
    suspend fun create(record: ExplorationRecord)

    /** Ends an exploration; an unknown id is refused with [IllegalArgumentException]. */
    suspend fun finish(
        id: ExplorationId,
        status: ExplorationStatus,
        endedAt: Instant,
        summary: ExplorationSummary,
        modelVersion: Int?,
    )

    suspend fun find(id: ExplorationId): ExplorationRecord?

    /** Newest first; all targets when [target] is null. */
    suspend fun list(
        target: URI? = null,
        limit: Int = 50,
    ): List<ExplorationRecord>
}

/** Site model versions, counted per target ([TargetKey]). */
interface SiteModelVersions {
    /**
     * Stores [model] exactly as given. Refused with [IllegalArgumentException] when its target already has that
     * version or its exploration already has a model, so two explorations can never claim the same version.
     */
    suspend fun saveModel(model: SiteModel)

    /** Highest stored version for [target], 0 when there is none. */
    suspend fun latestVersion(target: URI): Int

    suspend fun model(
        target: URI,
        version: Int,
    ): SiteModel?

    suspend fun model(explorationId: ExplorationId): SiteModel?

    /** Stored versions of [target], ascending. */
    suspend fun versions(target: URI): List<Int>

    /** The newest model of [target], or null when it was never explored. */
    suspend fun latestModel(target: URI): SiteModel? = latestVersion(target).takeIf { it > 0 }?.let { model(target, it) }
}

interface ExplorationFindings {
    suspend fun saveFinding(
        explorationId: ExplorationId,
        finding: ExplorationFinding,
    )

    /** In recording order. */
    suspend fun findings(explorationId: ExplorationId): List<ExplorationFinding>
}

/** The replayable event stream of each exploration. */
interface ExplorationEventLog {
    /** Appends [event]; a sequence number already stored for its exploration is refused with [IllegalArgumentException]. */
    suspend fun append(event: ExplorationEvent)

    /** Events of [explorationId] with a sequence number above [afterSeq], in order. */
    suspend fun events(
        explorationId: ExplorationId,
        afterSeq: Long = 0,
    ): List<ExplorationEvent>

    /** Highest stored sequence number of [explorationId], 0 when it has no events. */
    suspend fun lastSeq(explorationId: ExplorationId): Long
}

/**
 * Records of the evidence files an exploration wrote (screenshots, DOM snapshots, HTTP answers). The model refers to
 * them by [ArtifactId]; the panel resolves an id to its record here and to its file with the evidence artifact store.
 */
interface ExplorationArtifacts {
    /** Stores [record] (its run id is the exploration's [ExplorationId.evidenceKey]); storing the same id again replaces it. */
    suspend fun saveArtifact(record: ArtifactRecord)

    suspend fun artifact(id: ArtifactId): ArtifactRecord?

    /** Artifacts of [explorationId] in recording order. */
    suspend fun artifacts(explorationId: ExplorationId): List<ArtifactRecord>
}

interface ScenarioDrafts {
    suspend fun saveDraft(draft: ScenarioDraft)

    suspend fun draft(id: String): ScenarioDraft?

    /** Drafts generated from [explorationId], oldest first. */
    suspend fun drafts(explorationId: ExplorationId): List<ScenarioDraft>
}

/**
 * Everything the explorer stores per exploration id, and the latest model per target. Split into small interfaces so
 * each use case depends only on what it uses; one adapter implements them all.
 */
interface ExplorationRepository :
    ExplorationRecords,
    SiteModelVersions,
    ExplorationFindings,
    ExplorationEventLog,
    ExplorationArtifacts,
    ScenarioDrafts

/**
 * Confirms, from a source of truth outside the explorer, that writes land in test data only (docs/TARGET_CONTRACT.md:
 * the company must be `is_test`), before TRIAL_TOUCH submits anything. The composition root implements it with the
 * target's test API; without one, [REFUSE_ALL] keeps the explorer read-only.
 */
fun interface TestTargetCheck {
    suspend fun check(target: URI): TestTargetVerdict

    companion object {
        val REFUSE_ALL: TestTargetCheck = TestTargetCheck { TestTargetVerdict.Refused("no is_test check is configured") }
    }
}

sealed interface TestTargetVerdict {
    /** [evidence] says how the target was confirmed, e.g. `company c1 is_test=true`. */
    data class Confirmed(
        val evidence: String,
    ) : TestTargetVerdict

    data class Refused(
        val reason: String,
    ) : TestTargetVerdict
}
