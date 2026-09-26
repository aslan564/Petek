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

import az.petek.agent.domain.AgentRuntime

/** Creates [DefaultTesterAgent]s that share one stateless [loop] and one [registry] of run functions. */
class DefaultTesterAgentFactory(
    private val loop: AgentLoop,
    private val registry: RunFunctionRegistry,
) : TesterAgentFactory {
    override fun create(runtime: AgentRuntime): TesterAgent = DefaultTesterAgent(runtime, loop, registry)
}
