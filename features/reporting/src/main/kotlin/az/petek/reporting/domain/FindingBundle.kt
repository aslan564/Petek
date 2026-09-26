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

package az.petek.reporting.domain

import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord

/**
 * Everything about one finding a coding AI needs to look for its root cause in the site's repository (Faza 11): the
 * finding with its A/B/C sources and evidence tier, the step it happened in (action, timing, detail, correlation id),
 * and its evidence: the screenshot's path, and the text of HTTP exchanges, oracle answers, mails and logs inline.
 */
data class FindingBundle(
    val finding: FindingRecord,
    val target: String,
    val step: StepRecord?,
    val evidence: List<BundleEvidence>,
    /** The target's own log lines carrying the step's correlation id (Faza 14), when a [TraceSource] is configured. */
    val serverLog: List<String> = emptyList(),
)

/**
 * The correlation bridge (Faza 14): where the target's own logs are read for a correlation id (`X-Petek-Correlation-Id`
 * sent by the testers). The open core reads a log file; a hosted edition may read OpenTelemetry.
 */
fun interface TraceSource {
    suspend fun lines(correlationId: String): List<String>

    companion object {
        val NONE: TraceSource = TraceSource { emptyList() }
    }
}

/** One evidence file of a [FindingBundle]: its kind, where it is, and (for text kinds) what it says. */
data class BundleEvidence(
    val artifactId: String,
    val type: ArtifactType,
    val path: String,
    /** The file's text for HTTP, oracle, mail, log and prompt evidence, cut at a bounded size; null for images and DOM. */
    val text: String?,
)
