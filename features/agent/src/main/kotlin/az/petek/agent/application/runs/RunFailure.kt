package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.core.error.PetekException

/** Ends a run function with a reportable reason. Thrown inside flows, turned into an outcome by [RunEngine]. */
internal class RunFailure(
    val reason: FailureReason,
    override val message: String,
    val status: ActionStatus = ActionStatus.FAILED,
) : PetekException(message)
