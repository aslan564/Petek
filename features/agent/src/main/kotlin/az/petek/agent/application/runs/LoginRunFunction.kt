package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.StepContext

/** `login`: sign in with the agent's own credentials, then save the storage state for later restores. */
internal class LoginRunFunction(
    private val engine: RunEngine,
    private val flows: TargetFlows,
) : RunFunction {
    override val name: String = RunFunctions.LOGIN

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            flows.login(this)
            saveStorageState()
            succeeded("Logged in as ${runtime.identity.email}.")
        }
}
