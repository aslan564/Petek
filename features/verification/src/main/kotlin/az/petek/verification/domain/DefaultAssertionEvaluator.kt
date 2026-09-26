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

package az.petek.verification.domain

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.WaitOutcome
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.OracleCondition
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.oracle.domain.JsonFieldSelector
import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The code-only judge of typed assertions (CLAUDE.md rule 2): nothing here asks an LLM, and every time is taken
 * from the harness [clock] (rule 1).
 *
 * Contract beyond [AssertionEvaluator]:
 * - It never throws for a failing target, browser, oracle or template: such problems become a FAILED result with a
 *   note, so one broken check cannot hide the others. Only cancellation of the calling coroutine propagates; a
 *   `CancellationException` leaking out of a port while the caller is still active is a failed check.
 * - Templates in texts, selectors, paths and expected values are rendered with [AssertionInput.templates] before
 *   use; `expected` shows the rendered values. In `oracle` and `http_status` paths the substituted values are
 *   percent-encoded and the result must be a plain path on the target ([TargetPath]); anything else is FAILED
 *   without sending a request.
 * - `visible_text` measured against t0 waits only for what is left of `t0 + within`. When less than
 *   [MIN_WAIT] is left it checks once without waiting (a zero browser timeout would mean "wait forever") and
 *   reports the latency as an upper bound.
 * - Sources follow the three-source model: screen checks are RECEIVER, oracle and the target's own HTTP answers
 *   are ORACLE, `latency_max` is HARNESS and `only_one_succeeds` is SENDER.
 * - `only_one_succeeds` trusts only [ActorResult.succeeded] (derived by the caller from each actor's own requests,
 *   see [RaceEvidence]); its oracle condition is checked like an `oracle` assertion and its body kept as evidence
 *   ([RaceVerdict]).
 */
class DefaultAssertionEvaluator(
    private val oracle: TargetOracle,
    private val renderer: TemplateRenderer,
    fields: JsonFieldSelector,
    private val clock: HarnessClock,
) : AssertionEvaluator {
    private val matcher = OracleMatcher(fields)

    override suspend fun evaluate(
        specs: List<AssertionSpec>,
        input: AssertionInput,
    ): List<AssertionResult> {
        var lastVisibleText: VisibleTextCheck? = null
        return specs.map { spec ->
            when (spec) {
                is AssertionSpec.VisibleText -> {
                    visibleText(spec, input).also { lastVisibleText = it }.result
                }

                is AssertionSpec.LatencyMax -> {
                    latencyMax(spec, lastVisibleText)
                }

                is AssertionSpec.NotVisible -> {
                    guarded(spec, input) { notVisible(spec, input) }
                }

                is AssertionSpec.Count -> {
                    guarded(spec, input) { count(spec, input) }
                }

                is AssertionSpec.Oracle -> {
                    guarded(spec, input) { oracle(spec, input) }
                }

                is AssertionSpec.HttpStatus -> {
                    guarded(spec, input) { httpStatus(spec, input) }
                }

                is AssertionSpec.OnlyOneSucceeds -> {
                    result(spec, Verdict.SKIPPED, observed = null, note = "group-level: judged once per step over all actors")
                }
            }
        }
    }

    override fun evaluateOnlyOneSucceeds(results: List<ActorResult>): AssertionResult =
        RaceVerdict.judge(AssertionSpec.OnlyOneSucceeds(), results)

    override suspend fun evaluateOnlyOneSucceeds(
        spec: AssertionSpec.OnlyOneSucceeds,
        results: List<ActorResult>,
        input: AssertionInput,
    ): AssertionResult = RaceVerdict.judge(spec, results, spec.oracle?.let { raceOracle(it, input) })

    /**
     * The oracle condition of a race, with the rules of the `oracle` assertion: SKIPPED without a test API, FAILED for
     * an unsafe path, a template error, an unexpected answer or a failing oracle call.
     */
    private suspend fun raceOracle(
        condition: OracleCondition,
        input: AssertionInput,
    ): RaceVerdict.OracleCheck {
        val unrendered = AssertionText.asOracle(condition)
        if (!oracle.isAvailable) {
            return RaceVerdict.OracleCheck(Verdict.NOT_APPLICABLE, AssertionText.describe(unrendered), null, NO_ORACLE, null)
        }
        return try {
            val rendered = unrendered.rendered(input)
            val expected = AssertionText.describe(rendered)
            TargetPath.problem(rendered.path)?.let { problem ->
                val shown = AssertionText.clip(rendered.path, MAX_PATH_IN_NOTE)
                return RaceVerdict.OracleCheck(Verdict.FAILED, expected, null, "refused to request `$shown`: the path $problem", null)
            }
            val response = oracle.get(rendered.path)
            val match = matcher.match(rendered, response)
            RaceVerdict.OracleCheck(match.verdict, expected, match.observed, match.note, response.rawBody)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            RaceVerdict.OracleCheck(
                Verdict.FAILED,
                AssertionText.describe(unrendered),
                null,
                "check failed: ${AssertionText.error(e)}",
                null,
            )
        } catch (e: TemplateException) {
            RaceVerdict.OracleCheck(Verdict.FAILED, AssertionText.describe(unrendered), null, "template error: ${e.message}", null)
        } catch (e: Exception) {
            RaceVerdict.OracleCheck(
                Verdict.FAILED,
                AssertionText.describe(unrendered),
                null,
                "check failed: ${AssertionText.error(e)}",
                null,
            )
        }
    }

    // --- visible_text / latency_max -------------------------------------------------------------------------------

    /** A `visible_text` result plus whether its latency is only an upper bound (window elapsed before the check). */
    private class VisibleTextCheck(
        val result: AssertionResult,
        val latencyIsUpperBound: Boolean,
    )

    private suspend fun visibleText(
        spec: AssertionSpec.VisibleText,
        input: AssertionInput,
    ): VisibleTextCheck {
        var upperBound = false
        val result =
            guarded(spec, input) {
                val rendered = spec.rendered(input)
                val session = input.session ?: return@guarded noSession(spec, rendered)
                val t0 = input.eventEmittedAt
                val remaining =
                    if (t0 == null) spec.within else (spec.within - t0.elapsedUntil(clock.now())).coerceAtMost(spec.within)
                val waited = remaining >= MIN_WAIT
                val outcome = if (waited) session.waitForText(rendered.text, remaining) else checkNow(session, rendered.text)
                val latency =
                    if (t0 != null && outcome.found) {
                        t0.elapsedUntil(outcome.observedAt ?: clock.now()).coerceAtLeast(Duration.ZERO)
                    } else {
                        null
                    }
                upperBound = !waited && latency != null
                AssertionResult(
                    spec = spec,
                    verdict = if (outcome.found) Verdict.PASSED else Verdict.FAILED,
                    source = EvidenceSource.RECEIVER,
                    expected = AssertionText.describe(rendered),
                    observed = visibleTextObserved(outcome.found, latency, spec.within),
                    latency = latency,
                    note = if (waited) null else noWaitNote(outcome.found, remaining, spec.within, measuredFromT0 = t0 != null),
                )
            }
        return VisibleTextCheck(result, upperBound)
    }

    /** Zero-wait check: some browser APIs treat a zero timeout as "wait forever", so it is never passed down. */
    private suspend fun checkNow(
        session: BrowserSession,
        text: String,
    ): WaitOutcome = if (session.isTextVisible(text)) WaitOutcome(true, clock.now()) else WaitOutcome(false, null)

    private fun visibleTextObserved(
        found: Boolean,
        latency: Duration?,
        within: Duration,
    ): String =
        when {
            !found -> "not seen within ${AssertionText.ms(within)}"
            latency != null -> "seen after ${AssertionText.ms(latency)}"
            else -> "seen"
        }

    private fun noWaitNote(
        found: Boolean,
        remaining: Duration,
        within: Duration,
        measuredFromT0: Boolean,
    ): String {
        if (!measuredFromT0) return "no wait window (within = ${AssertionText.ms(within)}); checked once"
        val late =
            if (remaining.isPositive()) {
                "less than ${AssertionText.ms(MIN_WAIT)} of the ${AssertionText.ms(within)} window was left; checked once"
            } else {
                "the ${AssertionText.ms(within)} window had elapsed ${AssertionText.ms(-remaining)} before the check; checked once"
            }
        return if (found) "$late; latency is an upper bound" else late
    }

    private fun latencyMax(
        spec: AssertionSpec.LatencyMax,
        measured: VisibleTextCheck?,
    ): AssertionResult {
        val latency = measured?.result?.latency
        if (latency == null) {
            val reason =
                when {
                    measured == null -> "no preceding visible_text"
                    measured.result.verdict != Verdict.PASSED -> "the preceding visible_text failed"
                    else -> "the preceding visible_text had no event time (t0)"
                }
            return result(spec, Verdict.FAILED, observed = null, note = "no latency measured: $reason")
        }
        val withinLimit = latency <= spec.max
        val note =
            when {
                withinLimit -> {
                    null
                }

                measured.latencyIsUpperBound -> {
                    "${AssertionText.ms(latency)} exceeds ${AssertionText.ms(spec.max)}, but it is only an upper bound " +
                        "(the text was checked after its window had elapsed)"
                }

                else -> {
                    "${AssertionText.ms(latency)} exceeds ${AssertionText.ms(spec.max)}"
                }
            }
        return result(
            spec,
            if (withinLimit) Verdict.PASSED else Verdict.FAILED,
            observed = AssertionText.ms(latency),
            note = note,
        )
    }

    // --- other screen checks ----------------------------------------------------------------------------------------

    private suspend fun notVisible(
        spec: AssertionSpec.NotVisible,
        input: AssertionInput,
    ): AssertionResult {
        val rendered = spec.rendered(input)
        if (rendered.text == null && rendered.selector == null) {
            return result(spec, Verdict.FAILED, observed = null, note = "not_visible needs a text or a selector")
        }
        val session = input.session ?: return noSession(spec, rendered)
        val visible =
            listOfNotNull(
                rendered.text?.takeIf { session.isTextVisible(it) }?.let { "text ${AssertionText.quote(it)}" },
                rendered.selector?.takeIf { session.isSelectorVisible(it) }?.let { "selector `$it`" },
            )
        return result(
            spec,
            if (visible.isEmpty()) Verdict.PASSED else Verdict.FAILED,
            expected = AssertionText.describe(rendered),
            observed = if (visible.isEmpty()) "not visible" else "visible: " + visible.joinToString(" and "),
        )
    }

    private suspend fun count(
        spec: AssertionSpec.Count,
        input: AssertionInput,
    ): AssertionResult {
        val rendered = spec.rendered(input)
        val session = input.session ?: return noSession(spec, rendered)
        val actual = session.count(rendered.selector)
        return result(
            spec,
            if (actual == spec.equals) Verdict.PASSED else Verdict.FAILED,
            expected = AssertionText.describe(rendered),
            observed = actual.toString(),
        )
    }

    // --- target answers ---------------------------------------------------------------------------------------------

    private suspend fun oracle(
        spec: AssertionSpec.Oracle,
        input: AssertionInput,
    ): AssertionResult {
        if (!oracle.isAvailable) {
            return result(spec, Verdict.NOT_APPLICABLE, expected = describeBestEffort(spec, input), observed = null, note = NO_ORACLE)
        }
        val rendered = spec.rendered(input)
        TargetPath.problem(rendered.path)?.let { return unsafePath(spec, rendered, rendered.path, it) }
        val response = oracle.get(rendered.path)
        val match = matcher.match(rendered, response)
        return result(
            spec,
            match.verdict,
            expected = AssertionText.describe(rendered),
            observed = match.observed,
            note = match.note,
            rawEvidence = response.rawBody,
        )
    }

    private suspend fun httpStatus(
        spec: AssertionSpec.HttpStatus,
        input: AssertionInput,
    ): AssertionResult {
        val rendered = spec.rendered(input)
        TargetPath.problem(rendered.path)?.let { return unsafePath(spec, rendered, rendered.path, it) }
        val session = input.session ?: return noSession(spec, rendered)
        val response = session.request(rendered.method, rendered.path)
        val passed = response.status == spec.equals
        val body = AssertionText.utf8Prefix(response.body, AssertionText.MAX_HTTP_EVIDENCE_BYTES)
        return result(
            spec,
            if (passed) Verdict.PASSED else Verdict.FAILED,
            expected = AssertionText.describe(rendered),
            observed = response.status.toString(),
            note = if (passed) null else "target answered ${response.status}, expected ${spec.equals}",
            rawEvidence = "${response.status} $body",
        )
    }

    // --- rendering ----------------------------------------------------------------------------------------------------

    private fun template(
        value: String,
        input: AssertionInput,
    ): String = renderer.render(value, input.templates)

    /** Renders a request path with percent-encoded placeholder values, see [TargetPath]. */
    private fun pathTemplate(
        value: String,
        input: AssertionInput,
    ): String = renderer.render(value, TargetPath.encodeValues(input.templates))

    private fun AssertionSpec.VisibleText.rendered(input: AssertionInput) = copy(text = template(text, input))

    private fun AssertionSpec.NotVisible.rendered(input: AssertionInput) =
        copy(text = text?.let { template(it, input) }, selector = selector?.let { template(it, input) })

    private fun AssertionSpec.Count.rendered(input: AssertionInput) = copy(selector = template(selector, input))

    private fun AssertionSpec.Oracle.rendered(input: AssertionInput) =
        copy(
            path = pathTemplate(path, input),
            equals = equals?.let { template(it, input) },
            contains = contains?.let { template(it, input) },
        )

    private fun AssertionSpec.HttpStatus.rendered(input: AssertionInput) =
        copy(path = pathTemplate(path, input), method = method.trim().uppercase())

    /** `expected` for a result whose check could not run: rendered when possible, the raw templates otherwise. */
    private fun describeBestEffort(
        spec: AssertionSpec,
        input: AssertionInput,
    ): String {
        val shown =
            try {
                when (spec) {
                    is AssertionSpec.VisibleText -> spec.rendered(input)
                    is AssertionSpec.NotVisible -> spec.rendered(input)
                    is AssertionSpec.Count -> spec.rendered(input)
                    is AssertionSpec.Oracle -> spec.rendered(input)
                    is AssertionSpec.HttpStatus -> spec.rendered(input)
                    is AssertionSpec.LatencyMax, is AssertionSpec.OnlyOneSucceeds -> spec
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The renderer failing here already failed the check itself; show the raw templates instead.
                spec
            }
        return AssertionText.describe(shown)
    }

    // --- failures and result building ---------------------------------------------------------------------------------

    /**
     * Runs one check and turns any failure into a FAILED result with a note. A [CancellationException] propagates only
     * when the calling coroutine itself was cancelled; one leaking out of a port (e.g. a nested `withTimeout`) while
     * the caller is still active is that check failing, not a reason to abandon the remaining assertions.
     */
    private suspend inline fun guarded(
        spec: AssertionSpec,
        input: AssertionInput,
        check: () -> AssertionResult,
    ): AssertionResult =
        try {
            check()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            checkFailed(spec, input, e)
        } catch (e: TemplateException) {
            result(spec, Verdict.FAILED, expected = describeBestEffort(spec, input), observed = null, note = "template error: ${e.message}")
        } catch (e: Exception) {
            checkFailed(spec, input, e)
        }

    private fun checkFailed(
        spec: AssertionSpec,
        input: AssertionInput,
        error: Exception,
    ): AssertionResult =
        result(
            spec,
            Verdict.FAILED,
            expected = describeBestEffort(spec, input),
            observed = null,
            note = "${spec.type} check failed: ${AssertionText.error(error)}",
        )

    private fun unsafePath(
        spec: AssertionSpec,
        rendered: AssertionSpec,
        path: String,
        problem: String,
    ): AssertionResult =
        result(
            spec,
            Verdict.FAILED,
            expected = AssertionText.describe(rendered),
            observed = null,
            note = "refused to request `${AssertionText.clip(path, MAX_PATH_IN_NOTE)}`: the path $problem",
        )

    private fun noSession(
        spec: AssertionSpec,
        rendered: AssertionSpec,
    ): AssertionResult =
        result(
            spec,
            Verdict.FAILED,
            expected = AssertionText.describe(rendered),
            observed = null,
            note = "no browser session for this actor",
        )

    private fun result(
        spec: AssertionSpec,
        verdict: Verdict,
        expected: String = AssertionText.describe(spec),
        observed: String?,
        note: String? = null,
        rawEvidence: String? = null,
    ): AssertionResult =
        AssertionResult(
            spec = spec,
            verdict = verdict,
            source = sourceOf(spec),
            expected = expected,
            observed = observed,
            latency = null,
            note = note,
            rawEvidence = rawEvidence,
        )

    private fun sourceOf(spec: AssertionSpec): EvidenceSource =
        when (spec) {
            is AssertionSpec.VisibleText, is AssertionSpec.NotVisible, is AssertionSpec.Count -> EvidenceSource.RECEIVER
            is AssertionSpec.Oracle, is AssertionSpec.HttpStatus -> EvidenceSource.ORACLE
            is AssertionSpec.LatencyMax -> EvidenceSource.HARNESS
            is AssertionSpec.OnlyOneSucceeds -> EvidenceSource.SENDER
        }

    private companion object {
        /** The note of an oracle check on a target without a test API (Faza 10). */
        const val NO_ORACLE = "N/A (no oracle)"

        /**
         * Shortest window handed to the browser. An adapter may round a timeout down to whole milliseconds and
         * Playwright reads 0 ms as "no timeout", so a sub-millisecond remainder becomes a single check, never a wait.
         */
        val MIN_WAIT = 1.milliseconds

        const val MAX_PATH_IN_NOTE = 200
    }
}
