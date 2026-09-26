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

package az.petek.agent.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentAction
import az.petek.agent.domain.AgentDecision
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.DecisionParse
import az.petek.agent.domain.DecisionProtocol
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.LoopDetector
import az.petek.agent.domain.ProblemKind
import az.petek.agent.domain.StepContext
import az.petek.browser.domain.PageSnapshot
import az.petek.core.error.PetekException
import az.petek.core.ids.IdGenerator
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmRole
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.MailboxException
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.TargetOracle
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The `do` loop: snapshot -> LLM decision -> whitelist action -> evidence, until the model calls `done` /
 * `report_problem`, or a guard stops it. Guards, all decided by code (AGENTS.md rules 2 and 3):
 * - [StepContext.maxSteps] LLM decisions -> `step_limit`; the whole execution runs within [StepContext.timeout] -> `timeout`;
 * - the same action chosen repeatedly (per [LoopDetector]) -> `loop_detected`, the repeat is not executed;
 * - a ref that is not on the page, a placeholder that does not resolve or an absolute URL on another host is an
 *   invalid decision (fed back, nothing executed);
 * - [MAX_INVALID_DECISIONS] invalid decisions in a row -> `invalid_decision`;
 * - [MAX_FAILED_ACTIONS] failed browser/mail actions in a row -> `browser_error`;
 * - no verification e-mail for `get_email_code` -> `mail_timeout`; a test inbox that stayed unreachable for the
 *   whole wait -> `ERROR mail_unavailable` (an environment problem, not a finding about the target);
 * - an LLM that cannot answer -> `ERROR llm_unavailable` at once (retries belong to the LLM decorators).
 *
 * `get_phone_code` reads the agent's newest phone code from the target's test API ([TargetOracle.latestOtp]), asking
 * up to [PHONE_CODE_ATTEMPTS] times because the site may publish it a moment after asking for it. A missing code or an
 * unavailable test API is an observation for the model ("phone code unavailable"), which may then report a problem.
 *
 * `report_problem(permission_denied)` ends the step as BLOCKED/`permission_denied`: for forbidden-action tests that
 * is the expected outcome, and the verdict is left to the assertions. The orchestrator treats any other problem the
 * agent reports in such a step (one whose assertions test the refusal) the same way, so the verdict never depends on
 * the kind the model chose.
 *
 * Evidence per turn: one DO [az.petek.evidence.domain.StepRecord] (readable action, the model's reason, harness
 * timings, the scenario step's correlation id), a screenshot after every executed action, and the accessibility
 * tree on the first turn, the last turn and every turn that did not pass. JavaScript dialogs the page opened during
 * the turn (the browser accepts them) are added to the turn's observation ([dialogNote]), so the model sees them in
 * its history and the report in the step detail. The password never reaches the model or
 * the evidence: the model types `{self.password}`, and page text going back to it is redacted.
 *
 * Stateless between executions: one instance serves every agent concurrently.
 */
class DefaultAgentLoop(
    private val llm: LlmClient,
    private val protocol: DecisionProtocol,
    private val loopDetectorFactory: () -> LoopDetector,
    recorder: EvidenceRecorder,
    artifacts: ArtifactStore,
    private val verification: AwaitVerificationUseCase,
    /** Source of phone codes for `get_phone_code`; the target's test mode sends no SMS. */
    private val oracle: TargetOracle,
    private val prompts: PromptBuilder,
    private val resolver: PlaceholderResolver,
    clock: HarnessClock,
    ids: IdGenerator,
) : AgentLoop {
    private val evidence = StepEvidence(recorder, artifacts, clock, ids)

    override suspend fun execute(
        runtime: AgentRuntime,
        instruction: String,
        step: StepContext,
    ): ActionOutcome {
        val execution = Execution(runtime, instruction, step)
        val outcome = withTimeoutOrNull(step.timeout) { execution.run() } ?: execution.interrupted()
        logger.info {
            "${runtime.identity.agentId}/${step.scenarioStep}: do finished ${outcome.status}" +
                (outcome.failureReason?.let { " (${it.key})" } ?: "") + " after ${outcome.stepsTaken} decisions"
        }
        return outcome
    }

    /** State of one `do` execution. Confined to the coroutine that runs it. */
    private inner class Execution(
        private val runtime: AgentRuntime,
        private val task: String,
        private val step: StepContext,
    ) {
        private val detector = loopDetectorFactory()
        private val history = mutableListOf<ActionHistoryEntry>()
        private val system: String by lazy { runtime.redact(prompts.system(runtime)) }
        private val label = "${runtime.identity.agentId}/${step.scenarioStep}"
        private var decisions = 0
        private var turns = 0
        private var invalidStreak = 0
        private var failedActionStreak = 0

        /** The outcome of the turn that ended the loop, set before that turn's evidence is written. */
        private var concluded: ActionOutcome? = null

        suspend fun run(): ActionOutcome {
            while (decisions < step.maxSteps) {
                val startedAt = evidence.now()
                conclude(nextTurn(), startedAt)?.let { return it }
            }
            return finishWithoutTurn("step limit", failed(FailureReason.STEP_LIMIT, "Task not finished within ${step.maxSteps} decisions."))
        }

        /**
         * The step timeout fired. When the loop had already ended (the timeout hit while the last turn's evidence
         * was being captured), that ending stands: the model finished within its budget. Otherwise it is `timeout`.
         */
        suspend fun interrupted(): ActionOutcome =
            concluded ?: finishWithoutTurn(
                "timeout",
                failed(FailureReason.TIMEOUT, "Task not finished within ${step.timeout} ($decisions decisions made)."),
            )

        /**
         * The page as the model sees it. A single-page application answers `load` with an empty shell and draws the
         * page afterwards (seen on a real single-page application, 2026-09-26): an empty snapshot is retried every [PAGE_SETTLE_POLL] for at most
         * [PAGE_SETTLE_TIMEOUT], so the model's first decision and the step's first screenshot show the page, not the
         * shell. A page that stays empty is shown as it is; the model then reports what it sees.
         */
        private suspend fun settledSnapshot(): PageSnapshot {
            var snapshot = runtime.session.snapshot()
            if (snapshot.hasContent) return snapshot
            withTimeoutOrNull(PAGE_SETTLE_TIMEOUT) {
                do {
                    delay(PAGE_SETTLE_POLL)
                    snapshot = runtime.session.snapshot()
                } while (!snapshot.hasContent)
            }
            return snapshot
        }

        private val PageSnapshot.hasContent: Boolean get() = elements.isNotEmpty() || visibleText.isNotBlank()

        private suspend fun nextTurn(): Turn {
            val snapshot =
                try {
                    settledSnapshot()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Turn("snapshot", StepStatus.ERROR, "ERROR: could not read the page: ${describe(e)}", actionFailed = true)
                }
            val request = request(snapshot)
            val output =
                try {
                    llm.complete(request).output
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmException.InvalidOutput) {
                    decisions++
                    return invalid("Your answer was not a JSON object matching the schema (${describe(e)}).", reason = null)
                } catch (e: Exception) {
                    return llmUnavailable(e)
                }
            decisions++
            return when (val parsed = protocol.parse(output)) {
                is DecisionParse.Invalid -> invalid(parsed.error, output.reason())
                is DecisionParse.Valid -> decide(parsed.decision, snapshot)
            }
        }

        private fun request(snapshot: PageSnapshot): LlmRequest {
            val turn =
                PromptBuilder.Turn(
                    task = task,
                    history = history.toList(),
                    snapshot = snapshot,
                    decisionNumber = decisions + 1,
                    maxDecisions = step.maxSteps,
                    placeholders = resolver.available(runtime),
                )
            return LlmRequest(
                system = system,
                messages = listOf(LlmMessage(LlmRole.USER, prompts.user(runtime, turn))),
                responseSchema = protocol.responseSchema(),
                label = label,
            )
        }

        private suspend fun decide(
            decision: AgentDecision,
            snapshot: PageSnapshot,
        ): Turn {
            val action = decision.action
            val described = action.describe(snapshot)
            val reason = decision.reason
            return when (action) {
                is AgentAction.Done -> {
                    done(action, described, reason)
                }

                is AgentAction.ReportProblem -> {
                    problem(action, described, reason)
                }

                else -> {
                    when (val preparation = prepare(action, snapshot)) {
                        is Preparation.Rejected -> {
                            invalid(preparation.message, reason, described)
                        }

                        is Preparation.Ready -> {
                            if (detector.register(action)) {
                                loopDetected(described, reason)
                            } else {
                                perform(action, preparation.typedText, described, reason)
                            }
                        }
                    }
                }
            }
        }

        /**
         * Checks what the protocol cannot know: the ref exists on this page, every placeholder resolves, and an
         * absolute URL stays on the site under test (the target policy of AGENTS.md rule 8 is checked once for the
         * target; an agent must not wander to another host, such as production, because a page or its task says so).
         */
        private fun prepare(
            action: AgentAction,
            snapshot: PageSnapshot,
        ): Preparation {
            if (action is AgentAction.Navigate && !staysOnSite(action.url, snapshot.url)) {
                return Preparation.Rejected(
                    "Only pages of the site under test can be opened. Use a path such as /tickets" +
                        (hostOf(snapshot.url)?.let { " or an absolute URL on $it" } ?: "") + ".",
                )
            }
            val ref =
                when (action) {
                    is AgentAction.Click -> action.ref
                    is AgentAction.Type -> action.ref
                    is AgentAction.Select -> action.ref
                    else -> null
                }
            if (ref != null && snapshot.elements.none { it.ref == ref }) {
                return Preparation.Rejected("Element [$ref] is not on the current page. Use a ref from the Elements list.")
            }
            if (action !is AgentAction.Type) return Preparation.Ready(null)
            return when (val resolution = resolver.resolve(action.text, runtime)) {
                is PlaceholderResolver.Resolution.Resolved -> Preparation.Ready(resolution.text)
                is PlaceholderResolver.Resolution.Unresolved -> Preparation.Rejected(resolution.message)
            }
        }

        private suspend fun perform(
            action: AgentAction,
            typedText: String?,
            described: String,
            reason: String,
        ): Turn =
            try {
                when (action) {
                    AgentAction.GetEmailCode -> {
                        fetchEmailCode(described, reason)
                    }

                    AgentAction.GetPhoneCode -> {
                        fetchPhoneCode(described, reason)
                    }

                    else -> {
                        val (status, observation) = act(action, typedText)
                        Turn(described, status, observation, reason, validDecision = true, actionFailed = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Turn(described, StepStatus.ERROR, "ERROR: ${describe(e)}", reason, validDecision = true, actionFailed = true)
            }

        private suspend fun act(
            action: AgentAction,
            typedText: String?,
        ): Pair<StepStatus, String> {
            val session = runtime.session
            return when (action) {
                is AgentAction.Navigate -> {
                    session.navigate(action.url)
                    StepStatus.PASSED to "OK: opened ${action.url}."
                }

                is AgentAction.Click -> {
                    session.click(action.ref)
                    StepStatus.PASSED to "OK: clicked."
                }

                is AgentAction.Type -> {
                    session.fill(action.ref, requireNotNull(typedText), action.submit)
                    StepStatus.PASSED to if (action.submit) "OK: typed and pressed Enter." else "OK: typed."
                }

                is AgentAction.Select -> {
                    session.select(action.ref, action.option)
                    StepStatus.PASSED to "OK: selected ${quote(action.option)}."
                }

                is AgentAction.ReadText -> {
                    val text = session.readText(action.selector)
                    if (text == null) {
                        StepStatus.FAILED to "No element matches ${action.selector}."
                    } else {
                        StepStatus.PASSED to "OK: text is \"${text.trim().replace("\n", " ").clip(MAX_READ_CHARS)}\"."
                    }
                }

                is AgentAction.WaitText -> {
                    if (session.waitForText(action.text, action.timeout).found) {
                        StepStatus.PASSED to "OK: ${quote(action.text)} is visible."
                    } else {
                        StepStatus.FAILED to "${quote(action.text)} did not appear within ${action.timeout.inWholeSeconds}s."
                    }
                }

                AgentAction.GetEmailCode, AgentAction.GetPhoneCode, is AgentAction.Done, is AgentAction.ReportProblem -> {
                    error("${action.toolName} is not a browser action")
                }
            }
        }

        private suspend fun fetchEmailCode(
            described: String,
            reason: String,
        ): Turn {
            val code =
                try {
                    verification.await(runtime.identity.email, runtime.runStartedAt, MailPurpose.CODE)
                } catch (e: MailTimeoutException) {
                    val message = "No verification e-mail arrived within ${e.timeout}."
                    return Turn(
                        described,
                        StepStatus.FAILED,
                        message,
                        reason,
                        validDecision = true,
                        actionFailed = false,
                        outcome = failed(FailureReason.MAIL_TIMEOUT, message),
                    )
                } catch (e: MailboxException) {
                    val message = "Test inbox unreachable: ${describe(e)}"
                    return Turn(
                        described,
                        StepStatus.ERROR,
                        "ERROR: $message",
                        reason,
                        validDecision = true,
                        actionFailed = true,
                        outcome = ActionOutcome(ActionStatus.ERROR, message, failureReason = FailureReason.MAIL_UNAVAILABLE),
                    )
                }
            val value =
                code.code
                    ?: return Turn(
                        described,
                        StepStatus.ERROR,
                        "ERROR: the newest verification e-mail contains no code.",
                        reason,
                        validDecision = true,
                        actionFailed = true,
                    )
            runtime.variables[AgentVariableKeys.EMAIL_CODE] = value
            return Turn(
                described,
                StepStatus.PASSED,
                "OK: verification code stored; type {vars.${AgentVariableKeys.EMAIL_CODE}} to enter it.",
                reason,
                validDecision = true,
                actionFailed = false,
            )
        }

        /**
         * Stores the newest phone code as `{vars.phone_code}`. A code that is not there yet, a test API that fails or
         * one this run cannot use is told to the model; none of them ends the step by itself.
         */
        private suspend fun fetchPhoneCode(
            described: String,
            reason: String,
        ): Turn {
            fun turn(
                status: StepStatus,
                observation: String,
            ) = Turn(described, status, observation, reason, validDecision = true, actionFailed = status == StepStatus.ERROR)

            if (!oracle.isAvailable) {
                return turn(
                    StepStatus.FAILED,
                    "Phone code unavailable: this run has no access to the site's test API, the only source of phone codes.",
                )
            }
            var failure: OracleException? = null
            for (attempt in 1..PHONE_CODE_ATTEMPTS) {
                if (attempt > 1) delay(PHONE_CODE_RETRY_DELAY)
                val code =
                    try {
                        oracle.latestOtp(runtime.identity.phone).also { failure = null }
                    } catch (e: OracleException) {
                        failure = e
                        null
                    }
                if (!code.isNullOrBlank()) {
                    runtime.variables[AgentVariableKeys.PHONE_CODE] = code.trim()
                    return turn(StepStatus.PASSED, "OK: phone code stored; type {vars.${AgentVariableKeys.PHONE_CODE}} to enter it.")
                }
            }
            return failure?.let { turn(StepStatus.ERROR, "ERROR: phone code unavailable: ${describe(it)}") }
                ?: turn(
                    StepStatus.FAILED,
                    "No phone code has been sent to your phone yet (the test API was asked $PHONE_CODE_ATTEMPTS times).",
                )
        }

        private fun done(
            action: AgentAction.Done,
            described: String,
            reason: String,
        ): Turn {
            if (action.success) action.objectId?.let { runtime.variables[AgentVariableKeys.LAST_OBJECT_ID] = it }
            val outcome =
                if (action.success) {
                    ActionOutcome(ActionStatus.SUCCEEDED, action.summary, action.objectId)
                } else {
                    ActionOutcome(ActionStatus.FAILED, action.summary, action.objectId, FailureReason.PROBLEM_REPORTED)
                }
            val status = if (action.success) StepStatus.PASSED else StepStatus.FAILED
            return Turn(described, status, "Finished: ${action.summary}", reason, validDecision = true, outcome = outcome)
        }

        private fun problem(
            action: AgentAction.ReportProblem,
            described: String,
            reason: String,
        ): Turn {
            val denied = action.kind == ProblemKind.PERMISSION_DENIED
            val outcome =
                ActionOutcome(
                    status = if (denied) ActionStatus.BLOCKED else ActionStatus.FAILED,
                    // The failure reason already says permission_denied; other kinds are kept in front of the note.
                    summary = if (denied) action.note else "${action.kind.key}: ${action.note}",
                    failureReason = if (denied) FailureReason.PERMISSION_DENIED else FailureReason.PROBLEM_REPORTED,
                )
            val status = if (denied) StepStatus.BLOCKED else StepStatus.FAILED
            return Turn(described, status, "Problem reported: ${outcome.summary}", reason, validDecision = true, outcome = outcome)
        }

        private fun loopDetected(
            described: String,
            reason: String,
        ): Turn =
            Turn(
                described,
                StepStatus.FAILED,
                "Stopped: the same action was chosen again without progress; it was not executed.",
                reason,
                validDecision = true,
                outcome = failed(FailureReason.LOOP_DETECTED, "Loop detected: '$described' was chosen repeatedly in a row."),
            )

        private fun invalid(
            message: String,
            reason: String?,
            described: String = "invalid decision",
        ): Turn = Turn(described, StepStatus.FAILED, "INVALID: $message", reason, validDecision = false)

        private fun llmUnavailable(e: Exception): Turn {
            val message = "LLM unavailable: ${describe(e)}"
            return Turn(
                "llm decision",
                StepStatus.ERROR,
                message,
                outcome = ActionOutcome(ActionStatus.ERROR, message, failureReason = FailureReason.LLM_UNAVAILABLE),
            )
        }

        /** Updates the guards, records the turn with its artifacts and returns the outcome when the loop ends. */
        private suspend fun conclude(
            turn: Turn,
            startedAt: HarnessTimestamp,
        ): ActionOutcome? {
            turns++
            when (turn.validDecision) {
                true -> invalidStreak = 0
                false -> invalidStreak++
                null -> Unit
            }
            when (turn.actionFailed) {
                true -> failedActionStreak++
                false -> failedActionStreak = 0
                null -> Unit
            }
            val outcome =
                (turn.outcome ?: guardOutcome(turn))
                    ?.let { it.copy(summary = runtime.redact(it.summary), stepsTaken = decisions) }
                    ?.also { concluded = it }
            val observation = withNote(turn.observation, runtime.session.dialogNote())
            val detail =
                if (outcome == null) {
                    observation
                } else {
                    "$observation | outcome: ${outcome.status}${outcome.failureReason?.let {
                        " ${it.key}"
                    } ?: ""}: ${outcome.summary}"
                }
            val stepId = evidence.record(runtime, step, StepKind.DO, turn.action, turn.reason, startedAt, turn.status, detail)
            val last = outcome != null
            evidence.capture(
                runtime,
                stepId,
                screenshot = turn.actionFailed != null || last,
                accessibility = turns == 1 || last || turn.status != StepStatus.PASSED,
            )
            history += ActionHistoryEntry(turns, runtime.redact(turn.action), runtime.redact(observation))
            logger.debug { "$label turn $turns: ${runtime.redact(turn.action)} -> ${turn.status}" }
            return outcome
        }

        private fun guardOutcome(turn: Turn): ActionOutcome? =
            when {
                invalidStreak >= MAX_INVALID_DECISIONS -> {
                    failed(
                        FailureReason.INVALID_DECISION,
                        "$invalidStreak invalid decisions in a row; last: ${turn.observation}",
                    )
                }

                failedActionStreak >= MAX_FAILED_ACTIONS -> {
                    failed(FailureReason.BROWSER_ERROR, "$failedActionStreak failed actions in a row; last: ${turn.observation}")
                }

                decisions >= step.maxSteps -> {
                    failed(FailureReason.STEP_LIMIT, "Task not finished within ${step.maxSteps} decisions.")
                }

                else -> {
                    null
                }
            }

        private suspend fun finishWithoutTurn(
            action: String,
            outcome: ActionOutcome,
        ): ActionOutcome {
            val result = outcome.copy(stepsTaken = decisions)
            val detail = withNote(result.summary, runtime.session.dialogNote())
            val stepId = evidence.record(runtime, step, StepKind.DO, action, null, evidence.now(), StepStatus.FAILED, detail)
            evidence.capture(runtime, stepId, screenshot = true, accessibility = true)
            return result
        }

        private fun failed(
            reason: FailureReason,
            summary: String,
        ) = ActionOutcome(ActionStatus.FAILED, runtime.redact(summary), failureReason = reason)

        private fun describe(e: Throwable): String {
            val message = e.message ?: "no message"
            val text = if (e is PetekException) message else "${e::class.simpleName}: $message"
            return runtime.redact(text).clip(MAX_ERROR_CHARS)
        }
    }

    /** One turn of the loop as it is recorded and replayed to the model. */
    private class Turn(
        val action: String,
        val status: StepStatus,
        val observation: String,
        val reason: String? = null,
        /** true: a valid decision; false: an invalid one; null: no decision was made. */
        val validDecision: Boolean? = null,
        /** true: the executed action failed; false: it ran; null: nothing was executed. */
        val actionFailed: Boolean? = null,
        /** Set when this turn ends the loop. */
        val outcome: ActionOutcome? = null,
    )

    private sealed interface Preparation {
        /** [typedText] is the resolved `type` text; it may contain the password and must never be logged. */
        class Ready(
            val typedText: String?,
        ) : Preparation

        class Rejected(
            val message: String,
        ) : Preparation
    }

    private fun JsonObject.reason(): String? = (this["reason"] as? JsonPrimitive)?.contentOrNull

    /** A path always resolves against the session's base URL; an absolute URL must keep the current page's host. */
    private fun staysOnSite(
        url: String,
        currentUrl: String,
    ): Boolean {
        if (url.startsWith("/")) return true
        val current = hostOf(currentUrl) ?: return false
        return hostOf(url) == current
    }

    private fun hostOf(url: String): String? =
        try {
            URI(url).host?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (_: URISyntaxException) {
            null
        }

    companion object {
        /** Consecutive invalid decisions that end the loop. */
        const val MAX_INVALID_DECISIONS = 2

        /** Consecutive failed browser or mail actions that end the loop. */
        const val MAX_FAILED_ACTIONS = 3

        /** How often `get_phone_code` asks the test API before telling the model there is no code. */
        const val PHONE_CODE_ATTEMPTS = 5

        /** How long an empty page (a single-page application's shell) is given to draw before the model sees it. */
        val PAGE_SETTLE_TIMEOUT: Duration = 4.seconds
        val PAGE_SETTLE_POLL: Duration = 250.milliseconds

        /** Pause between two `get_phone_code` lookups: the site may publish the code a moment after asking for it. */
        val PHONE_CODE_RETRY_DELAY: Duration = 1.seconds

        private const val MAX_READ_CHARS = 500
        private const val MAX_ERROR_CHARS = 500
    }
}
