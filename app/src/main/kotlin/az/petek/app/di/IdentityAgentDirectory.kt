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

package az.petek.app.di

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.identity.domain.IdentityRepository
import az.petek.reporting.domain.AgentDirectory

/** Gives the report the display names of a run's testers (reporting cannot see the identity feature itself). */
class IdentityAgentDirectory(
    private val identities: IdentityRepository,
) : AgentDirectory {
    override suspend fun names(runId: RunId): Map<AgentId, String> = identities.findByRun(runId).associate { it.agentId to it.displayName }
}
