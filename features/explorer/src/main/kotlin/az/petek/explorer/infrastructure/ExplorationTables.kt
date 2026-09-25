/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.infrastructure

import org.jetbrains.exposed.v1.core.Table
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/*
 * SQLite schema of the explorer (docs/ARCHITECTURE.md, module features/explorer). Every table has an auto-increment
 * `seq` (insertion order, the tie-breaker of every ordering), strings are unbounded TEXT, instants are ISO-8601 UTC
 * text with nine fractional digits (text order is time order), and structured values are JSON (ExplorationJson.kt).
 */

/** Instants as fixed-width ISO-8601 UTC text, e.g. `2026-01-01T10:00:00.120000000Z`. */
internal object InstantText {
    private val format: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter(Locale.ROOT)

    fun encode(instant: Instant): String = format.format(instant)

    fun decode(text: String): Instant = Instant.parse(text)
}

internal object ExplorationTable : Table("exploration") {
    val seq = long("seq").autoIncrement()
    val explorationId = text("exploration_id")
    val targetKey = text("target_key")
    val target = text("target")
    val requestJson = text("request_json")
    val status = text("status")
    val startedAt = text("started_at")
    val endedAt = text("ended_at").nullable()
    val summaryJson = text("summary_json").nullable()
    val modelVersion = integer("model_version").nullable()

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(explorationId)
        index(false, targetKey, startedAt)
    }
}

internal object SiteModelTable : Table("site_model_version") {
    val seq = long("seq").autoIncrement()
    val explorationId = text("exploration_id")
    val targetKey = text("target_key")
    val version = integer("version")
    val createdAt = text("created_at")
    val modelJson = text("model_json")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(targetKey, version)
        uniqueIndex(explorationId)
    }
}

internal object FindingTable : Table("exploration_finding") {
    val seq = long("seq").autoIncrement()
    val explorationId = text("exploration_id")
    val findingId = text("finding_id")
    val kind = text("kind")
    val severity = text("severity")
    val pageUrl = text("page_url")
    val detail = text("detail")
    val role = text("role")
    val evidenceJson = text("evidence_json")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(findingId)
        index(false, explorationId)
    }
}

internal object EventTable : Table("exploration_event") {
    val seq = long("seq").autoIncrement()
    val explorationId = text("exploration_id")
    val eventSeq = long("event_seq")
    val type = text("type")
    val at = text("at")
    val payloadJson = text("payload_json")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(explorationId, eventSeq)
    }
}

internal object ArtifactTable : Table("exploration_artifact") {
    val seq = long("seq").autoIncrement()
    val artifactId = text("artifact_id")
    val explorationId = text("exploration_id")
    val stepId = text("step_id")
    val type = text("type")
    val relativePath = text("relative_path")
    val sha256 = text("sha256")
    val sizeBytes = long("size_bytes")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(artifactId)
        index(false, explorationId)
    }
}

internal object DraftTable : Table("scenario_draft") {
    val seq = long("seq").autoIncrement()
    val draftId = text("draft_id")
    val explorationId = text("exploration_id")
    val targetKey = text("target_key")
    val target = text("target")
    val modelVersion = integer("model_version")
    val name = text("name")
    val yaml = text("yaml")
    val coveredJson = text("covered_json")
    val skippedJson = text("skipped_json")
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(draftId)
        index(false, explorationId)
    }
}

internal val explorationTables: Array<Table> =
    arrayOf(ExplorationTable, SiteModelTable, FindingTable, EventTable, ArtifactTable, DraftTable)
