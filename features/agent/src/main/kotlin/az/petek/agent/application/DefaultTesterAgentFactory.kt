package az.petek.agent.application

import az.petek.agent.domain.AgentRuntime

/** Creates [DefaultTesterAgent]s that share one stateless [loop] and one [registry] of run functions. */
class DefaultTesterAgentFactory(
    private val loop: AgentLoop,
    private val registry: RunFunctionRegistry,
) : TesterAgentFactory {
    override fun create(runtime: AgentRuntime): TesterAgent = DefaultTesterAgent(runtime, loop, registry)
}
