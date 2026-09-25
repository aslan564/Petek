package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.campaign.domain.FlowNames

/**
 * `verify_identity`: the target profile's `verify_identity` flow, whose `assert_identity` requires the page's current
 * user to be this agent (`identity_mismatch` otherwise). This is the proof that the agents' browser contexts do not
 * share sessions.
 */
internal class VerifyIdentityRunFunction(
    private val engine: RunEngine,
    private val flows: FlowRunner,
) : RunFunction {
    override val name: String = RunFunctions.VERIFY_IDENTITY

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            val progress = FlowProgress()
            flows.run(this, FlowNames.VERIFY_IDENTITY, progress, FailureReason.LOGIN_FAILED)
            succeeded((progress.identityShown ?: "The session was checked") + ".")
        }
}
