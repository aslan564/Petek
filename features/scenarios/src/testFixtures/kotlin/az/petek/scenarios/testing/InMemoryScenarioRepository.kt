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

import az.petek.scenarios.domain.ConcurrentScenarioChangeException
import az.petek.scenarios.domain.FrozenScenarioException
import az.petek.scenarios.domain.NewScenarioVersion
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.ScenarioVersionUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory [ScenarioRepository] with the same guarantees as the SQLite one: consecutive version numbers
 * per name, all-or-nothing compare-and-set updates, FROZEN versions never change, one APPROVED version per name.
 */
class InMemoryScenarioRepository : ScenarioRepository {
    private val mutex = Mutex()
    private val versions = LinkedHashMap<ScenarioVersionId, ScenarioVersion>()

    /** Updates applied so far, in order (for tests that check what a use case changed). */
    val appliedUpdates = mutableListOf<ScenarioVersionUpdate>()

    override suspend fun add(draft: NewScenarioVersion): ScenarioVersion =
        mutex.withLock {
            require(draft.id !in versions) { "Scenario version ${draft.id} already exists" }
            val next = (versions.values.filter { it.name == draft.name }.maxOfOrNull { it.version } ?: 0) + 1
            draft.toVersion(next).also { versions[it.id] = it }
        }

    override suspend fun find(id: ScenarioVersionId): ScenarioVersion? = mutex.withLock { versions[id] }

    override suspend fun history(name: String): List<ScenarioVersion> =
        mutex.withLock { versions.values.filter { it.name == name }.sortedBy { it.version } }

    override suspend fun all(): List<ScenarioVersion> =
        mutex.withLock { versions.values.sortedWith(compareBy({ it.name }, { it.version })) }

    override suspend fun withHash(sha256: String): List<ScenarioVersion> =
        mutex.withLock { versions.values.filter { it.sha256 == sha256 }.sortedWith(compareBy({ it.createdAt }, { it.version })) }

    override suspend fun update(updates: List<ScenarioVersionUpdate>) {
        mutex.withLock {
            val staged = LinkedHashMap(versions)
            updates.forEach { update ->
                val stored = staged[update.id] ?: throw ScenarioNotFoundException(update.id)
                if (stored.status == ScenarioStatus.FROZEN) throw FrozenScenarioException(update.id)
                if (stored.status != update.before.status) throw ConcurrentScenarioChangeException(update.id)
                val after = update.after
                val approvedElsewhere =
                    staged.values.any {
                        it.name == after.name && it.id != after.id &&
                            it.status == ScenarioStatus.APPROVED
                    }
                if (after.status == ScenarioStatus.APPROVED && approvedElsewhere) throw ConcurrentScenarioChangeException(update.id)
                staged[update.id] = after
            }
            versions.clear()
            versions.putAll(staged)
            appliedUpdates += updates
        }
    }
}
