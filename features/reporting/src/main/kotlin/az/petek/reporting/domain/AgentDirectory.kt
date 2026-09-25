/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId

/**
 * Display names of a run's agents for the report. Reporting depends only on evidence, not on the identity feature,
 * so the composition root adapts the identity repository to this port. Agents without a name are shown by id.
 */
fun interface AgentDirectory {
    suspend fun names(runId: RunId): Map<AgentId, String>

    companion object {
        /** No names known: the report falls back to agent ids. */
        val NONE: AgentDirectory = AgentDirectory { emptyMap() }
    }
}
