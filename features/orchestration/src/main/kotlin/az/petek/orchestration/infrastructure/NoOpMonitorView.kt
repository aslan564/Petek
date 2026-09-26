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

package az.petek.orchestration.infrastructure

import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary

/** Monitor that ignores everything (tests, `--quiet`). Stateless, so a single instance is shared. */
object NoOpMonitorView : MonitorView {
    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) = Unit

    override fun agentUpdated(status: AgentStatus) = Unit

    override fun stepStarted(scenarioStep: String) = Unit

    override fun message(text: String) = Unit

    override fun runFinished(summary: RunSummary) = Unit
}
