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

package az.petek.explorer.application

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.IdGenerator
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationFinding
import az.petek.explorer.domain.ExplorationFindings
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.ExplorationSummary
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.domain.Keywords
import az.petek.explorer.domain.LinkPolicy
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.Severity
import az.petek.explorer.domain.SiteModelAccumulator
import az.petek.explorer.domain.SiteOrigin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Records findings once each (per kind and page, or per broken address), stores them and announces them. Storing and
 * remembering a finding happen together or not at all, also when the exploration is cancelled meanwhile, so the
 * result's findings always equal the stored ones.
 */
internal class FindingRecorder(
    private val explorationId: ExplorationId,
    private val store: ExplorationFindings,
    private val emitter: ExplorationEmitter,
    private val ids: IdGenerator,
) {
    private val recorded = mutableListOf<ExplorationFinding>()
    private val keys = HashSet<String>()

    val all: List<ExplorationFinding> get() = recorded.toList()

    val count: Int get() = recorded.size

    suspend fun record(
        kind: FindingKind,
        severity: Severity,
        pageUrl: String,
        detail: String,
        role: String,
        evidence: List<ArtifactId>,
        key: String = "$kind|$pageUrl|$role",
    ): ExplorationFinding? {
        if (!keys.add(key)) return null
        val finding = ExplorationFinding(ids.findingId(), kind, severity, pageUrl, detail, role, evidence.distinct())
        withContext(NonCancellable) {
            store.saveFinding(explorationId, finding)
            recorded += finding
        }
        emitter.emit { ExplorationEvent.FindingRecorded(it, finding) }
        return finding
    }
}

/** State shared by the phases of one exploration. Used from the exploring coroutine only. */
internal class ExplorationContext(
    val id: ExplorationId,
    val request: ExplorationRequest,
    val settings: ExplorerSettings,
    val accumulator: SiteModelAccumulator,
    val emitter: ExplorationEmitter,
    val analyst: PageAnalyst,
    val capture: PageCapture,
    val findings: FindingRecorder,
    private val clock: HarnessClock,
    val startedAt: HarnessTimestamp,
) {
    val origin: SiteOrigin = SiteOrigin.of(request.target)
    val keywords: Set<String> = Keywords.of(request.grounding)
    var policy: LinkPolicy = LinkPolicy(origin)
    var robotsChecked = false
    val pagesVisitedByRole = LinkedHashMap<String, Int>()
    var pageBudgetReached = false
    var timedOut = false
        private set
    val phasesRun = mutableListOf<ExplorationPhase>()
    val phasesSkipped = LinkedHashMap<ExplorationPhase, String>()
    val notes = mutableListOf<String>()

    /** The harness clock's now, e.g. to ask the browser what a page reported since it started loading. */
    fun now(): HarnessTimestamp = clock.now()

    /** True once the time budget is used up (measured by the harness clock); remembered as a timeout. */
    fun deadlinePassed(): Boolean {
        if (!timedOut && startedAt.elapsedUntil(clock.now()) >= request.budget.timeLimit) timedOut = true
        return timedOut
    }

    suspend fun modelUpdated() {
        emitter.emit { ExplorationEvent.ModelUpdated(it, accumulator.counts(findings.count)) }
    }

    suspend fun raiseUnknown(
        question: String,
        context: String,
        pageId: String?,
        provenance: Provenance,
        evidence: List<ArtifactId>,
    ) {
        val unknown = accumulator.recordUnknown(question, context, pageId, provenance, evidence) ?: return
        emitter.emit { ExplorationEvent.UnknownRaised(it, unknown) }
    }

    fun summary(status: ExplorationStatus): ExplorationSummary =
        ExplorationSummary(
            status = status,
            counts = accumulator.counts(findings.count),
            pagesVisitedByRole = pagesVisitedByRole.toMap(),
            llmCalls = analyst.calls,
            llmAnswersRejected = analyst.answersRejected,
            durationMs = startedAt.elapsedUntil(clock.now()).inWholeMilliseconds,
            pageBudgetReached = pageBudgetReached,
            phasesRun = phasesRun.toList(),
            phasesSkipped = phasesSkipped.toMap(),
            notes = (notes + listOfNotNull(analyst.disabledReason)).distinct(),
        )
}
