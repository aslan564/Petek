/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.application

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The artifacts the dashboard has seen, so the server can serve a file only when it was recorded for the run on the
 * board: clients name an artifact id (or the in-run path the report links with), never a file path. Kept outside the
 * immutable [az.petek.dashboard.domain.DashboardState] because it grows with every artifact of a run.
 *
 * Artifacts of other runs are dropped when the board moves to a new run ([focus]); lookups also check the run, so a
 * record that slips in concurrently is never served for the wrong run.
 */
internal class ArtifactIndex {
    private val byId = ConcurrentHashMap<ArtifactId, ArtifactRecord>()
    private val byPath = ConcurrentHashMap<String, ArtifactRecord>()
    private val focused = AtomicReference<RunId?>(null)

    fun add(record: ArtifactRecord) {
        byId[record.artifactId] = record
        byPath[normalized(record.relativePath)] = record
    }

    fun byId(
        runId: RunId,
        artifactId: ArtifactId,
    ): ArtifactRecord? = byId[artifactId]?.takeIf { it.runId == runId }

    fun byPathInRun(
        runId: RunId,
        pathInRun: String,
    ): ArtifactRecord? = byPath["${runId.value}/${normalized(pathInRun)}"]?.takeIf { it.runId == runId }

    /** Drops every artifact of another run when the board has moved to [runId]. */
    fun focus(runId: RunId) {
        if (focused.getAndSet(runId) == runId) return
        byId.values.removeIf { it.runId != runId }
        byPath.values.removeIf { it.runId != runId }
    }

    /** Shows exactly [records] of [runId] from now on (a replayed run). */
    fun replaceWith(
        runId: RunId,
        records: List<ArtifactRecord>,
    ) {
        focused.set(runId)
        byId.clear()
        byPath.clear()
        records.filter { it.runId == runId }.forEach(::add)
    }

    private fun normalized(path: String): String = path.replace('\\', '/').trimStart('/')
}
