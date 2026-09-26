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

package az.petek.orchestration.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Supplies extra coroutine context for the run and for each agent's work, so the composition root can attach a
 * logging context element (SLF4J MDC with `run_id` / `agent_id`, AGENTS.md "Loglarda MDC") without this feature
 * depending on a logging backend. [agentId] is null for harness-level work.
 */
fun interface DiagnosticContext {
    fun of(
        runId: RunId,
        agentId: AgentId?,
    ): CoroutineContext

    companion object {
        val NONE: DiagnosticContext = DiagnosticContext { _, _ -> EmptyCoroutineContext }
    }
}
