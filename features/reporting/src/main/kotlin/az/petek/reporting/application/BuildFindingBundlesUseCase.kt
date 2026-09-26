/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.application

import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.RunRepository
import az.petek.reporting.domain.BundleEvidence
import az.petek.reporting.domain.FindingBundle
import az.petek.reporting.domain.RunNotFoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files

/** Builds the [FindingBundle]s of a finished run (all, or the one with a given id), reading text evidence from disk. */
class BuildFindingBundlesUseCase(
    private val runs: RunRepository,
    private val query: EvidenceQuery,
    private val artifacts: ArtifactStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun bundles(
        runId: RunId,
        findingId: FindingId? = null,
    ): List<FindingBundle> {
        val run = runs.find(runId) ?: throw RunNotFoundException(runId)
        val findings = query.findings(runId).filter { findingId == null || it.findingId == findingId }
        if (findings.isEmpty()) return emptyList()
        val steps = query.steps(runId).associateBy { it.stepId }
        val evidence = query.artifacts(runId).associateBy { it.artifactId }
        return withContext(io) {
            findings.map { finding ->
                FindingBundle(
                    finding = finding,
                    target = run.target,
                    step = finding.stepId?.let(steps::get),
                    evidence =
                        finding.artifactIds.mapNotNull { id ->
                            val record = evidence[id] ?: return@mapNotNull null
                            val path = artifacts.resolve(record)
                            BundleEvidence(id.value, record.type, path.toString(), if (record.type in TEXT_TYPES) text(path) else null)
                        },
                )
            }
        }
    }

    private fun text(path: java.nio.file.Path): String? =
        try {
            Files.newInputStream(path).use { it.readNBytes(MAX_TEXT_BYTES) }.decodeToString()
        } catch (_: IOException) {
            null
        }

    private companion object {
        const val MAX_TEXT_BYTES = 16 * 1024
        val TEXT_TYPES = setOf(ArtifactType.HTTP, ArtifactType.ORACLE, ArtifactType.MAIL, ArtifactType.LOG, ArtifactType.PROMPT)
    }
}
