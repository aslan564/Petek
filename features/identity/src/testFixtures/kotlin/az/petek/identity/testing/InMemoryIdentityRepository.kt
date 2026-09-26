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

package az.petek.identity.testing

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRepository
import az.petek.identity.domain.IdentityStatus
import java.util.concurrent.ConcurrentHashMap

class InMemoryIdentityRepository : IdentityRepository {
    private val byRun = ConcurrentHashMap<RunId, MutableList<Identity>>()
    val statusReasons = ConcurrentHashMap<Pair<RunId, AgentId>, String>()

    override suspend fun replaceAll(
        runId: RunId,
        plan: IdentityPlan,
    ) {
        byRun[runId] = plan.identities.toMutableList()
    }

    override suspend fun findByRun(runId: RunId): List<Identity> = byRun[runId].orEmpty().toList()

    override suspend fun updateStatus(
        runId: RunId,
        agentId: AgentId,
        status: IdentityStatus,
        reason: String?,
    ) {
        update(runId, agentId) { it.copy(status = status) }
        if (reason != null) statusReasons[runId to agentId] = reason
    }

    override suspend fun updateStorageState(
        runId: RunId,
        agentId: AgentId,
        path: String,
    ) = update(runId, agentId) { it.copy(storageStatePath = path) }

    private fun update(
        runId: RunId,
        agentId: AgentId,
        change: (Identity) -> Identity,
    ) {
        val list = byRun[runId] ?: return
        synchronized(list) {
            val i = list.indexOfFirst { it.agentId == agentId }
            if (i >= 0) list[i] = change(list[i])
        }
    }
}
