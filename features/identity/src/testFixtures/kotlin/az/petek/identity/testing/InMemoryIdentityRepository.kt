/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
