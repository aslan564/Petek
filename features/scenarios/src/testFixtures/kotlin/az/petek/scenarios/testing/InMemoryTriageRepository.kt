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

package az.petek.scenarios.testing

import az.petek.core.ids.RunId
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.Surprise
import az.petek.scenarios.domain.SurpriseId
import az.petek.scenarios.domain.TriageFailure
import az.petek.scenarios.domain.TriageRepository
import az.petek.scenarios.domain.TriageVerdict
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thread-safe in-memory [TriageRepository] with the ordering and replacement rules of the SQLite one. */
class InMemoryTriageRepository : TriageRepository {
    private val mutex = Mutex()
    private val surprises = LinkedHashMap<SurpriseId, Surprise>()
    private val verdicts = LinkedHashMap<SurpriseId, TriageVerdict>()
    private val failures = LinkedHashMap<SurpriseId, TriageFailure>()

    override suspend fun addSurprises(surprises: List<Surprise>) {
        mutex.withLock { surprises.forEach { this.surprises.putIfAbsent(it.id, it) } }
    }

    override suspend fun surprises(runId: RunId): List<Surprise> = mutex.withLock { surprises.values.filter { it.runId == runId } }

    override suspend fun saveVerdict(verdict: TriageVerdict) {
        mutex.withLock {
            failures.remove(verdict.surpriseId)
            verdicts[verdict.surpriseId] = verdict
        }
    }

    override suspend fun verdict(surpriseId: SurpriseId): TriageVerdict? = mutex.withLock { verdicts[surpriseId] }

    override suspend fun verdicts(runId: RunId): List<TriageVerdict> = mutex.withLock { verdicts.values.filter { it.runId == runId } }

    override suspend fun verdictsForDraft(draftId: ScenarioVersionId): List<TriageVerdict> =
        mutex.withLock { verdicts.values.filter { it.proposedChange?.draftId == draftId } }

    override suspend fun saveFailure(failure: TriageFailure) {
        mutex.withLock { failures[failure.surpriseId] = failure }
    }

    override suspend fun failures(runId: RunId): List<TriageFailure> = mutex.withLock { failures.values.filter { it.runId == runId } }
}
