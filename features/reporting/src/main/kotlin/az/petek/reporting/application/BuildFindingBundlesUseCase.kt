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
import az.petek.reporting.domain.TraceSource
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
    private val traces: TraceSource = TraceSource.NONE,
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
        val bundles =
            withContext(io) {
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
        return bundles.map { bundle ->
            val correlation = bundle.step?.correlationId?.value ?: return@map bundle
            bundle.copy(serverLog = traces.lines(correlation))
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
