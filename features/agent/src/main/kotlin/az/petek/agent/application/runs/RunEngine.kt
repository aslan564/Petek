package az.petek.agent.application.runs

import az.petek.agent.application.StepEvidence
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.browser.domain.BrowserActionException
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.MailboxException
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.OracleSafetyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one run function inside its [StepContext.timeout], turns expected failures into outcomes and records the
 * concluding evidence. Every run function goes through here, so they all fail the same way for the same cause.
 */
internal class RunEngine(
    private val evidence: StepEvidence,
) {
    suspend fun execute(
        function: String,
        runtime: AgentRuntime,
        step: StepContext,
        body: suspend RunTrace.() -> ActionOutcome,
    ): ActionOutcome {
        val trace = RunTrace(evidence, runtime, step, function)
        val outcome =
            withTimeoutOrNull(step.timeout) { trace.outcomeOf(body) }
                ?: failed(FailureReason.TIMEOUT, "run $function did not finish within ${step.timeout}.")
        return trace.conclude(outcome)
    }
}

/**
 * Runs [body], mapping the failures a run function can expect to an outcome. Cancellation always propagates.
 * An unreachable test inbox ([MailboxException], left over after the mail use case retried it for its whole timeout)
 * is an environment problem: `ERROR mail_unavailable`, never a generic error or a finding about the target.
 */
internal suspend fun RunTrace.outcomeOf(body: suspend RunTrace.() -> ActionOutcome): ActionOutcome =
    try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RunFailure) {
        ActionOutcome(e.status, e.message, failureReason = e.reason)
    } catch (e: MailTimeoutException) {
        failed(FailureReason.MAIL_TIMEOUT, e.message.orEmpty())
    } catch (e: MailboxException) {
        ActionOutcome(ActionStatus.ERROR, "Test inbox unreachable: ${e.message}", failureReason = FailureReason.MAIL_UNAVAILABLE)
    } catch (e: BrowserActionException) {
        failed(FailureReason.BROWSER_ERROR, "Browser error: ${e.message}")
    } catch (e: OracleException) {
        failed(FailureReason.MISSING_PREREQUISITE, "Test API call failed: ${e.message}")
    } catch (e: OracleSafetyException) {
        failed(FailureReason.MISSING_PREREQUISITE, "Test API refused: ${e.message}")
    } catch (e: Exception) {
        ActionOutcome(ActionStatus.ERROR, "Unexpected ${e::class.simpleName}: ${e.message}")
    }

internal fun succeeded(
    summary: String,
    objectId: String? = null,
) = ActionOutcome(ActionStatus.SUCCEEDED, summary, objectId)

internal fun failed(
    reason: FailureReason,
    summary: String,
) = ActionOutcome(ActionStatus.FAILED, summary, failureReason = reason)
