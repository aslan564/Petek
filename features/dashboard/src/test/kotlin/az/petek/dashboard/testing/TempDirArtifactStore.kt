/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.testing

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Writes artifacts to a real directory with the store's layout `<run>/<owner>/<seq>-<type>.<ext>`. */
class TempDirArtifactStore(
    root: Path,
    private val idPrefix: String = "art",
) : ArtifactStore {
    val root: Path = root.toAbsolutePath().normalize()
    private val ids = AtomicInteger()
    private val sequences = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun write(
        runId: RunId,
        stepId: StepId,
        owner: String,
        type: ArtifactType,
        bytes: ByteArray,
    ): ArtifactRecord {
        val seq = sequences.computeIfAbsent("${runId.value}/$owner") { AtomicInteger() }.incrementAndGet()
        val relative = "${runId.value}/$owner/${seq.toString().padStart(4, '0')}-${type.name.lowercase()}.${type.extension}"
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return ArtifactRecord(ArtifactId("${idPrefix}_${ids.incrementAndGet()}"), runId, stepId, type, relative, sha, bytes.size.toLong())
    }

    override fun resolve(record: ArtifactRecord): Path {
        val path = root.resolve(record.relativePath).normalize()
        require(path.startsWith(root)) { "outside the root: ${record.relativePath}" }
        return path
    }

    override fun runDirectory(runId: RunId): Path = root.resolve(runId.value)
}
