/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
