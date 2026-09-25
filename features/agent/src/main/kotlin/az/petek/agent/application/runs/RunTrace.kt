package az.petek.agent.application.runs

import az.petek.agent.application.StepEvidence
import az.petek.agent.application.quote
import az.petek.agent.application.redact
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.StepContext
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * One execution of a run function: the browser primitives it may use, each recorded as a RUN step
 * (`login: fill login.email "…"`), and the concluding `run <name>` step with a screenshot (plus the
 * accessibility tree when it did not succeed). Elements are addressed by [az.petek.campaign.domain.TargetProfile]
 * keys, so the evidence reads the same whatever selectors a campaign overrides. The password is typed only through
 * [fillPassword] and shows as `***`.
 */
internal class RunTrace(
    private val evidence: StepEvidence,
    val runtime: AgentRuntime,
    private val step: StepContext,
    private val function: String,
) {
    private val startedAt = evidence.now()
    private val session get() = runtime.session
    private val target get() = runtime.target

    /** Recorded sub-actions so far (reported as [ActionOutcome.stepsTaken]). */
    var subActions: Int = 0
        private set

    /** Runs [block] as one recorded sub-action: PASSED when it returns, ERROR (and rethrown) when it throws. */
    suspend fun <T> act(
        description: String,
        block: suspend () -> T,
    ): T = probe(description, block) { true }

    /** Like [act], but the step is FAILED when the returned value does not satisfy [passed]. */
    suspend fun <T> probe(
        description: String,
        block: suspend () -> T,
        passed: (T) -> Boolean,
    ): T {
        val started = evidence.now()
        subActions++
        val result =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                record(description, started, StepStatus.ERROR, errorDetail(e))
                throw e
            }
        record(description, started, if (passed(result)) StepStatus.PASSED else StepStatus.FAILED, null)
        return result
    }

    /** Like [act] for something that is looked up: the step is FAILED, not PASSED, when nothing was found. */
    suspend fun <T : Any> lookup(
        description: String,
        block: suspend () -> T?,
    ): T? = probe(description, block) { it != null }

    /** Records an observation that took no browser action, e.g. `e-mail code rejected`. */
    suspend fun note(
        description: String,
        status: StepStatus,
        detail: String? = null,
    ) {
        subActions++
        record(description, evidence.now(), status, detail)
    }

    suspend fun open(pathKey: String) {
        val path = target.path(pathKey)
        act("open $path") { session.navigate(path) }
    }

    suspend fun openUrl(url: String) = act("open $url") { session.navigate(url) }

    suspend fun fill(
        key: String,
        value: String,
    ) = act("fill $key ${quote(value)}") { session.fillSelector(target.selector(key), value) }

    suspend fun fillPassword(key: String) =
        act("fill $key \"***\"") { session.fillSelector(target.selector(key), runtime.identity.password.reveal()) }

    suspend fun click(key: String) = act("click $key") { session.clickSelector(target.selector(key)) }

    suspend fun select(
        key: String,
        option: String,
    ) = act("select $key ${quote(option)}") { session.selectSelector(target.selector(key), option) }

    suspend fun waitFor(
        key: String,
        timeout: Duration,
    ): Boolean = probe("wait for $key", { session.waitForSelector(target.selector(key), timeout).found }) { it }

    suspend fun readText(key: String): String? = lookup("read $key") { session.readText(target.selector(key)) }

    suspend fun saveStorageState() = act("save storage state") { session.saveStorageState(runtime.storageStatePath) }

    /** Unrecorded visibility check, for polling loops that would otherwise flood the evidence. */
    suspend fun isVisible(key: String): Boolean = session.isSelectorVisible(target.selector(key))

    suspend fun currentUrl(): String = session.currentUrl()

    /** Records the function's own step and its artifacts; returns [outcome] with the redacted summary and step count. */
    suspend fun conclude(outcome: ActionOutcome): ActionOutcome {
        val result = outcome.copy(summary = runtime.redact(outcome.summary), stepsTaken = subActions)
        val status =
            when (result.status) {
                ActionStatus.SUCCEEDED -> StepStatus.PASSED
                ActionStatus.FAILED -> StepStatus.FAILED
                ActionStatus.BLOCKED -> StepStatus.BLOCKED
                ActionStatus.ERROR -> StepStatus.ERROR
            }
        val detail = (result.failureReason?.let { "${it.key}: " } ?: "") + result.summary
        val stepId = evidence.record(runtime, step, StepKind.RUN, "run $function", null, startedAt, status, detail)
        evidence.capture(runtime, stepId, screenshot = true, accessibility = !result.succeeded)
        return result
    }

    private suspend fun record(
        description: String,
        started: HarnessTimestamp,
        status: StepStatus,
        detail: String?,
    ) {
        evidence.record(runtime, step, StepKind.RUN, "$function: $description", null, started, status, detail)
    }
}
