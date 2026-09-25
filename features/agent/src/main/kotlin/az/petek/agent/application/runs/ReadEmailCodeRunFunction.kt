package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.StepContext

/** `read_email_code`: waits for the newest verification code since the run started and stores it as `{vars.email_code}`. */
internal class ReadEmailCodeRunFunction(
    private val engine: RunEngine,
    private val flows: TargetFlows,
) : RunFunction {
    override val name: String = RunFunctions.READ_EMAIL_CODE

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            flows.awaitEmailCode(this)
            succeeded("Verification code stored as {vars.${AgentVariableKeys.EMAIL_CODE}}.")
        }
}
