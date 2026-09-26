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
