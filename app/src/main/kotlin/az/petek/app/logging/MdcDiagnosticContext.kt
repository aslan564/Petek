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

package az.petek.app.logging

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.orchestration.application.DiagnosticContext
import kotlin.coroutines.CoroutineContext

/**
 * Attaches `run_id` and `agent_id` to every log line written while the runner works for a run or an agent
 * (CLAUDE.md: "Loglarda MDC"). Harness-level work has no agent, so `agent_id` is cleared for it.
 */
object MdcDiagnosticContext : DiagnosticContext {
    const val RUN_ID = "run_id"
    const val AGENT_ID = "agent_id"

    override fun of(
        runId: RunId,
        agentId: AgentId?,
    ): CoroutineContext = MdcContext(mapOf(RUN_ID to runId.value, AGENT_ID to agentId?.value))
}
