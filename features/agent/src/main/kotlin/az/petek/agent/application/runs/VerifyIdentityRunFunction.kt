package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.StepContext

/**
 * `verify_identity`: the page's current user must be this agent (`identity_mismatch` otherwise). This is the proof
 * that the agents' browser contexts do not share sessions.
 */
internal class VerifyIdentityRunFunction(
    private val engine: RunEngine,
    private val flows: TargetFlows,
) : RunFunction {
    override val name: String = RunFunctions.VERIFY_IDENTITY

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome = engine.execute(name, runtime, step) { succeeded(flows.verifyIdentity(this) + ".") }
}
