/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

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
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.LookRequest
import az.petek.browser.domain.LookShotKind
import az.petek.browser.domain.PageLook
import az.petek.browser.domain.PageTiming
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    /** Like [act], with [detail] of the result kept in the step's detail (e.g. which e-mail a code came from). */
    suspend fun <T> act(
        description: String,
        detail: (T) -> String?,
        block: suspend () -> T,
    ): T = probeDescribed(block, { description }, detail) { true }

    /** Like [act], but the step is FAILED when the returned value does not satisfy [passed]. */
    suspend fun <T> probe(
        description: String,
        block: suspend () -> T,
        passed: (T) -> Boolean,
    ): T = probeDescribed(block, { description }, passed = passed)

    /**
     * Like [probe], for a sub-action best described by what it found (`wait for session.user_name`). [describe] gets
     * the result, or null when [block] threw.
     */
    suspend fun <T> probeDescribed(
        block: suspend () -> T,
        describe: (T?) -> String,
        detail: (T) -> String? = { null },
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
        val noted = listOfNotNull(detail(result), session.dialogNote()).joinToString(" ").ifEmpty { null }
        record(describe(result), started, if (passed(result)) StepStatus.PASSED else StepStatus.FAILED, noted)
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

    /**
     * Publishes [value] as the run's shared [key] for the other testers. Shared values are write-once: when another
     * tester published a different one first, that one is kept, the conflict is recorded as a sub-action, and the kept
     * value is returned, so nothing is changed silently under the others.
     */
    suspend fun publish(
        key: String,
        value: String,
    ): String {
        if (runtime.shared.put(key, value)) return value
        val kept = runtime.shared.get(key) ?: value
        if (kept !=
            value
        ) {
            note("publish shared.$key", StepStatus.PASSED, "already published as '$kept'; kept (write-once), this step said '$value'")
        }
        return kept
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

    /**
     * Reads how fast the current page ([page], on [device]) became usable, as the browser timed it, and records it as the
     * page's timing (`site_health`'s `perf`); null when the session cannot read it.
     */
    suspend fun timing(
        page: String,
        device: String?,
    ): PageTiming? {
        val timing = act("read the timing of $page") { session.pageTiming() } ?: return null
        lastStepId?.let { evidence.pageTiming(runtime, step, it, page, device, timing) }
        return timing
    }

    /**
     * Takes a look of the current page ([page], on [device]) for comparing releases (`site_health`'s `look`) and keeps
     * it: its frames as visual artifacts of this sub-action, then the page look record ([StepEvidence.pageLook]). A
     * look never fails the step: one the session cannot take, that the browser refuses or that takes longer than
     * [lookTimeout] is a SKIPPED sub-action saying why, and null is returned. So is one taken but not kept (its frames or
     * record could not be written): a SKIPPED sub-action after it says so, since the look has no evidence to compare.
     */
    suspend fun look(
        page: String,
        device: String?,
        request: LookRequest,
    ): PageLook? {
        val on = device?.let { " ($it)" } ?: ""
        val description = "look at $page$on"
        val started = evidence.now()
        subActions++

        suspend fun notCaptured(why: String): PageLook? {
            record(description, started, StepStatus.SKIPPED, withNote("not captured: $why", session.dialogNote()))
            return null
        }

        val timeout = lookTimeout(request)
        val taken =
            try {
                // Wrapped, so a session that cannot take a look (null) is told apart from the time running out (null).
                withTimeoutOrNull(timeout) { Taken(session.look(request)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BrowserActionException) {
                return notCaptured(errorDetail(e) ?: e::class.simpleName.orEmpty())
            } ?: return notCaptured("timed out after $timeout")
        val look = taken.look ?: return notCaptured(NO_LOOK)
        if (look.shots.none { it.kind == LookShotKind.MAIN }) return notCaptured("the browser gave no main frame")
        val stepId = record(description, started, StepStatus.PASSED, withNote(LookNotes.of(look, page), session.dialogNote()))
        evidence.pageLook(runtime, step, stepId, page, device, look, request.maxHeight)?.let { problem ->
            note("keep the look of $page$on", StepStatus.SKIPPED, "not kept: $problem")
            return null
        }
        return look
    }

    /** Unrecorded visibility check, for polling loops that would otherwise flood the evidence. */
    suspend fun isVisible(ref: String): Boolean = session.isSelectorVisible(selector(ref))

    /** Unrecorded attribute read, e.g. whether a checkbox is already ticked. */
    suspend fun attribute(
        ref: String,
        name: String,
    ): String? = session.readAttribute(selector(ref), name)

    suspend fun currentUrl(): String = session.currentUrl()

    /** Harness time now, for windows of what the page reported ([az.petek.browser.domain.BrowserSession.health]). */
    fun now(): HarnessTimestamp = evidence.now()

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
    ): StepId {
        val stepId = evidence.record(runtime, step, StepKind.RUN, "$function: $description", null, started, status, detail)
        lastStepId = stepId
        return stepId
    }

    /** What [BrowserSession.look][az.petek.browser.domain.BrowserSession.look] gave, null included. */
    private class Taken(
        val look: PageLook?,
    )

    companion object {
        /** Why the session gave no look: it cannot take one, or the page went elsewhere or answered otherwise meanwhile. */
        const val NO_LOOK =
            "this session cannot take looks, or the page did not stay the same during the look (another address or answer)"

        private const val MAX_SELECTOR_CHARS = 80

        /** The least time a look is given, whatever it asks for: a default look's two loads take well under it. */
        val MIN_LOOK_TIMEOUT = 30.seconds

        /** The most time a look is given, below the agent's inactivity watchdog (120 s by default). */
        val MAX_LOOK_TIMEOUT = 60.seconds

        /**
         * Besides its settle budget, what one load of a look may take: opening the page again, waiting for the network
         * to go idle, its frames half a second apart and reading the page.
         */
        val LOOK_LOAD_ALLOWANCE = 12.seconds

        /**
         * The longest [request] may take: each load's settle budget, which a page that never calms down uses up, plus
         * [LOOK_LOAD_ALLOWANCE], within [MIN_LOOK_TIMEOUT] and [MAX_LOOK_TIMEOUT] (30 s for the default look, 54 s for
         * two loads of 15 s each). A look given less than its own settings ask for could never be kept.
         */
        fun lookTimeout(request: LookRequest): Duration =
            ((request.settle + LOOK_LOAD_ALLOWANCE) * request.loads).coerceIn(MIN_LOOK_TIMEOUT, MAX_LOOK_TIMEOUT)
    }
}
