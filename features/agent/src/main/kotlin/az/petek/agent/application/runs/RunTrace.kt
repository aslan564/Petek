package az.petek.agent.application.runs

import az.petek.agent.application.StepEvidence
import az.petek.agent.application.clip
import az.petek.agent.application.dialogNote
import az.petek.agent.application.quote
import az.petek.agent.application.redact
import az.petek.agent.application.withNote
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.StepContext
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * One execution of a run function: the browser primitives it may use, each recorded as a RUN step
 * (`login: fill login.email "…"`), and the concluding `run <name>` step with a screenshot (plus the
 * accessibility tree when it did not succeed). Elements are addressed by selector references (see
 * [az.petek.campaign.domain.Flow]): a [az.petek.campaign.domain.TargetProfile] key such as `login.email`, whose selector
 * the campaign may override while the evidence keeps reading the same, or a literal selector, shown as written. Secret
 * values are typed only through [fillPassword] and [fillMasked] and show as `***`. JavaScript dialogs a sub-action
 * made the page open (the browser accepts them) are noted in that sub-action's detail, and any left over in the
 * concluding step's detail.
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

    /** The most recently recorded sub-action, for evidence captured about it afterwards ([captureScreenshot]). */
    private var lastStepId: StepId? = null

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
    ): T = probeDescribed(block, { description }, passed)

    /**
     * Like [probe], for a sub-action best described by what it found (`wait for session.user_name`). [describe] gets
     * the result, or null when [block] threw.
     */
    suspend fun <T> probeDescribed(
        block: suspend () -> T,
        describe: (T?) -> String,
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
                val reason = errorDetail(e) ?: e::class.simpleName.orEmpty()
                record(describe(null), started, StepStatus.ERROR, withNote(reason, session.dialogNote()))
                throw e
            }
        record(describe(result), started, if (passed(result)) StepStatus.PASSED else StepStatus.FAILED, session.dialogNote())
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

    /** Opens a page by path key (`login`) or path (`/login`). */
    suspend fun open(pathRef: String) {
        val path = target.resolvePath(pathRef)
        act("open $path") { session.navigate(path) }
    }

    suspend fun openUrl(url: String) = act("open $url") { session.navigate(url) }

    suspend fun fill(
        ref: String,
        value: String,
    ) = act("fill ${describe(ref)} ${quote(value)}") { session.fillSelector(selector(ref), value) }

    suspend fun fillPassword(ref: String) = fillMasked(ref, runtime.identity.password.reveal())

    /** Types a secret [value] (a password rendered from a flow template); the evidence shows `***`. */
    suspend fun fillMasked(
        ref: String,
        value: String,
    ) = act("fill ${describe(ref)} \"***\"") { session.fillSelector(selector(ref), value) }

    suspend fun click(ref: String) = act("click ${describe(ref)}") { session.clickSelector(selector(ref)) }

    suspend fun select(
        ref: String,
        option: String,
    ) = act("select ${describe(ref)} ${quote(option)}") { session.selectSelector(selector(ref), option) }

    suspend fun waitFor(
        ref: String,
        timeout: Duration,
    ): Boolean = probe("wait for ${describe(ref)}", { session.waitForSelector(selector(ref), timeout).found }) { it }

    suspend fun readText(ref: String): String? = lookup("read ${describe(ref)}") { session.readText(selector(ref)) }

    suspend fun saveStorageState() = act("save storage state") { session.saveStorageState(runtime.storageStatePath) }

    /** Unrecorded visibility check, for polling loops that would otherwise flood the evidence. */
    suspend fun isVisible(ref: String): Boolean = session.isSelectorVisible(selector(ref))

    /** Unrecorded attribute read, e.g. whether a checkbox is already ticked. */
    suspend fun attribute(
        ref: String,
        name: String,
    ): String? = session.readAttribute(selector(ref), name)

    suspend fun currentUrl(): String = session.currentUrl()

    /** The selector [ref] stands for (see the class KDoc). */
    fun selector(ref: String): String = target.resolveSelector(ref)

    /** How [ref] reads in evidence: the key itself, or the literal selector shortened. */
    fun describe(ref: String): String = if (target.isSelectorKey(ref)) ref else ref.clip(MAX_SELECTOR_CHARS)

    /** A screenshot of the page now, attached to the most recent sub-action (e.g. the one a flow failed at). */
    suspend fun captureScreenshot() {
        lastStepId?.let { evidence.capture(runtime, it, screenshot = true, accessibility = false) }
    }

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
        val detail = withNote((result.failureReason?.let { "${it.key}: " } ?: "") + result.summary, session.dialogNote())
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
        lastStepId = evidence.record(runtime, step, StepKind.RUN, "$function: $description", null, started, status, detail)
    }

    private companion object {
        const val MAX_SELECTOR_CHARS = 80
    }
}
