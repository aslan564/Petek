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

package az.petek.agent.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.StepContext
import az.petek.campaign.domain.StepAction

/** Executes a natural-language `do` step: observe -> decide (LLM) -> act (whitelist) -> record, until done. */
interface AgentLoop {
    suspend fun execute(
        runtime: AgentRuntime,
        instruction: String,
        step: StepContext,
    ): ActionOutcome
}

/** A deterministic `run` step implemented in code. No LLM involved (AGENTS.md rule 6). */
interface RunFunction {
    /** Name used in the campaign YAML, e.g. `register_and_login`. */
    val name: String

    suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome
}

class RunFunctionRegistry(
    functions: List<RunFunction>,
) {
    private val byName: Map<String, RunFunction> = functions.associateBy { it.name }

    init {
        require(byName.size == functions.size) { "Duplicate run function names: ${functions.map { it.name }}" }
    }

    val names: Set<String> get() = byName.keys

    operator fun get(name: String): RunFunction? = byName[name]
}

/** One tester agent: performs any scenario action with its own identity and browser session. */
interface TesterAgent {
    val runtime: AgentRuntime

    suspend fun perform(
        action: StepAction,
        step: StepContext,
    ): ActionOutcome
}

/** Port used by the orchestrator to turn a prepared runtime (identity + session) into a working agent. */
fun interface TesterAgentFactory {
    fun create(runtime: AgentRuntime): TesterAgent
}
