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
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.TextWatch
import az.petek.browser.domain.WaitOutcome
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.OracleCondition
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
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
 * The code-only judge of typed assertions (AGENTS.md rule 2): nothing here asks an LLM, and every time is taken
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
 * - `visible_text` measured against t0 ([AssertionInput.eventTime]: the write when the emitter's page showed it) uses
 *   the receiver's watch when there is one for the same text ([AssertionInput.watch], Faza 24.10): a text it saw appear
 *   is timed then, one the page showed before the change was written proves nothing: INCONCLUSIVE, `stale_text`.
 *   Otherwise it waits only for what is left of `t0 + within`; without a watch it checks the page first, and a text
 *   visible at once gives only an upper bound. When less than [MIN_WAIT] is left it checks once without waiting (a
 *   zero browser timeout would mean "wait forever"). A text seen, but only after `t0 + within` for certain, fails.
 * - `latency_max` compares the range the delay lies in, not only the measured value: it passes when even the longest
 *   possible delay is within the limit and fails when even the shortest exceeds it; when only a bound is known (the
 *   text was there at the first look, the write was not seen) and the limit lies inside the range, it is INCONCLUSIVE,
 *   with a note saying so (Faza 24.12).
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

    /** A `visible_text` result plus, when it measured a latency, the range the real delivery latency lies in. */
    private class VisibleTextCheck(
        val result: AssertionResult,
        val range: LatencyRange? = null,
        /** [AssertionInput.earlierDelivery]: there was no delivery to time, so a following `latency_max` does not apply. */
        val earlierDelivery: String? = null,
    )

    /**
     * Where the real latency lies: [low] <= real <= [high], [measured] (from t0) in between. The range is a point when
     * both ends are known: the write (the emitter's request) and the moment the text appeared (a watch, or a wait that
     * saw it appear). [upperBoundBecause] and [lowerBoundBecause] say why an end is open.
     */
    private class LatencyRange(
        val measured: Duration,
        val low: Duration,
        val high: Duration,
        val upperBoundBecause: String?,
        val lowerBoundBecause: String?,
    )

    /** When the text was found ([at]); [appeared] is false when it was visible already at the first look. */
    private class Sighting(
        val at: HarnessTimestamp,
        val appeared: Boolean,
    )

    private suspend fun visibleText(
        spec: AssertionSpec.VisibleText,
        input: AssertionInput,
    ): VisibleTextCheck {
        var range: LatencyRange? = null
        val result =
            guarded(spec, input) {
                val rendered = spec.rendered(input)
                val session = input.session ?: return@guarded noSession(spec, rendered)
                input.earlierDelivery?.let { reason ->
                    val shown = untimed(spec, rendered, session)
                    return@guarded shown.copy(note = listOfNotNull(shown.note, reason).joinToString("; "))
                }
                val time = input.eventTime ?: return@guarded untimed(spec, rendered, session)
                val reading = input.watch?.takeIf { it.text == rendered.text }?.reading
                if (reading == TextWatch.WasThere) return@guarded seenBeforeTheWrite(spec, rendered, time)
                val remaining = (spec.within - time.t0.elapsedUntil(clock.now())).coerceAtMost(spec.within)
                val sighting =
                    sight(session, rendered.text, remaining, reading)
                        ?: return@guarded notSeen(spec, rendered, noWaitNote(false, remaining, spec.within, measuredFromT0 = true))
                val measured = latencyRange(time, sighting).also { range = it }
                val late = measured.low > spec.within
                AssertionResult(
                    spec = spec,
                    verdict = if (late) Verdict.FAILED else Verdict.PASSED,
                    source = EvidenceSource.RECEIVER,
                    expected = AssertionText.describe(rendered),
                    observed = visibleTextObserved(true, measured.measured, spec.within),
                    latency = measured.measured,
                    note = sightingNote(time, sighting, measured, remaining, spec.within, late),
                )
            }
        return VisibleTextCheck(result, range.takeIf { result.verdict == Verdict.PASSED }, input.earlierDelivery)
    }

    /**
     * Finds the text for a receiver whose event has a time: a watch that saw it appear gives that moment; a watch still
     * waiting, or none, waits for what is left of the window (a text that shows up meanwhile is timed as it appears).
     * Without a watch the page is checked first: a text visible at once was there before anyone looked, so that
     * moment is only an upper bound. Null when the text was not seen.
     */
    private suspend fun sight(
        session: BrowserSession,
        text: String,
        remaining: Duration,
        reading: TextWatch?,
    ): Sighting? {
        when (reading) {
            is TextWatch.Seen -> return Sighting(reading.at, appeared = true)
            TextWatch.NotYet -> Unit
            else -> if (session.isTextVisible(text)) return Sighting(clock.now(), appeared = false)
        }
        if (remaining < MIN_WAIT) {
            // The watch saw nothing a moment ago: a text there now appeared since. Without a watch it was checked above.
            return if (reading == TextWatch.NotYet) checkNow(session, text).observedAt?.let { Sighting(it, appeared = true) } else null
        }
        val outcome = session.waitForText(text, remaining)
        return if (outcome.found) Sighting(outcome.observedAt ?: clock.now(), appeared = true) else null
    }

    private fun latencyRange(
        time: EventTime,
        sighting: Sighting,
    ): LatencyRange {
        val measured = time.t0.elapsedUntil(sighting.at).coerceAtLeast(Duration.ZERO)
        val high = time.earliest.elapsedUntil(sighting.at).coerceAtLeast(Duration.ZERO)
        val low = if (sighting.appeared) time.latest.elapsedUntil(sighting.at).coerceAtLeast(Duration.ZERO) else Duration.ZERO
        val upper =
            listOfNotNull(
                "the text was visible already when first checked".takeUnless { sighting.appeared },
                "t0 is ${time.source}".takeIf { !time.exact && time.t0 == time.earliest },
            ).joinToString(" and ").ifEmpty { null }
        val lower = "t0 is ${time.source}".takeIf { !time.exact && time.t0 != time.earliest }
        return LatencyRange(measured, low.coerceAtMost(measured), high.coerceAtLeast(measured), upper, lower)
    }

    /** A `visible_text` without an event time (no `wait_for`): it only has to be seen within its window. */
    private suspend fun untimed(
        spec: AssertionSpec.VisibleText,
        rendered: AssertionSpec.VisibleText,
        session: BrowserSession,
    ): AssertionResult {
        val waited = spec.within >= MIN_WAIT
        val outcome = if (waited) session.waitForText(rendered.text, spec.within) else checkNow(session, rendered.text)
        return AssertionResult(
            spec = spec,
            verdict = if (outcome.found) Verdict.PASSED else Verdict.FAILED,
            source = EvidenceSource.RECEIVER,
            expected = AssertionText.describe(rendered),
            observed = visibleTextObserved(outcome.found, null, spec.within),
            latency = null,
            note = if (waited) null else noWaitNote(outcome.found, spec.within, spec.within, measuredFromT0 = false),
        )
    }

    private fun notSeen(
        spec: AssertionSpec.VisibleText,
        rendered: AssertionSpec.VisibleText,
        note: String?,
    ): AssertionResult =
        AssertionResult(
            spec = spec,
            verdict = Verdict.FAILED,
            source = EvidenceSource.RECEIVER,
            expected = AssertionText.describe(rendered),
            observed = visibleTextObserved(false, null, spec.within),
            latency = null,
            note = note,
        )

    /**
     * The receiver's page showed the text before the change was written: whatever it shows now cannot be told apart
     * from that earlier text (the same text published again in a later wave, the account swap, or text the page always
     * shows), so it proves no delivery and measures no latency.
     */
    private fun seenBeforeTheWrite(
        spec: AssertionSpec.VisibleText,
        rendered: AssertionSpec.VisibleText,
        time: EventTime,
    ): AssertionResult =
        AssertionResult(
            spec = spec,
            verdict = Verdict.INCONCLUSIVE,
            source = EvidenceSource.RECEIVER,
            expected = AssertionText.describe(rendered),
            observed = "visible before the change was written",
            latency = null,
            note =
                "$STALE_TEXT: the receiver's page showed ${AssertionText.quote(rendered.text)} already when the emitting step " +
                    "began, before ${time.source}, so seeing it proves no delivery; use a text only this change shows",
        )

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

    /** What the evidence should know about a sighting that is not plainly "appeared X ms after the write". */
    private fun sightingNote(
        time: EventTime,
        sighting: Sighting,
        range: LatencyRange,
        remaining: Duration,
        within: Duration,
        late: Boolean,
    ): String? =
        listOfNotNull(
            if (!sighting.appeared && remaining < MIN_WAIT) {
                noWaitNote(true, remaining, within, measuredFromT0 = true)
            } else {
                range.upperBoundBecause?.let { "latency is an upper bound: $it" }
            },
            range.lowerBoundBecause?.let {
                "the delay may be up to ${AssertionText.ms(range.high)} ($it, and the change was written after the action began)"
            },
            time.t0
                .elapsedUntil(sighting.at)
                .takeIf { it.isNegative() }
                ?.let { "the text appeared ${AssertionText.ms(-it)} before t0, counted as 0 ms" },
            "seen only after the ${AssertionText.ms(within)} window".takeIf { late },
        ).joinToString("; ").ifEmpty { null }

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

    /**
     * `latency_max` over the latency of the preceding `visible_text`: PASSED when even the longest the delivery can
     * have taken is within [AssertionSpec.LatencyMax.max], FAILED when even the shortest exceeds it, and INCONCLUSIVE
     * when the measurement cannot tell (only a bound of the delay is known) or the text proved no delivery.
     */
    private fun latencyMax(
        spec: AssertionSpec.LatencyMax,
        measured: VisibleTextCheck?,
    ): AssertionResult {
        val range = measured?.range
        if (range == null) {
            measured?.earlierDelivery?.let { reason ->
                return result(spec, Verdict.NOT_APPLICABLE, observed = null, note = "no delivery to time here: $reason")
            }
            if (measured?.result?.verdict == Verdict.INCONCLUSIVE) {
                val note = "no latency measured: the preceding visible_text proves no delivery"
                return result(spec, Verdict.INCONCLUSIVE, observed = null, note = note)
            }
            val reason =
                when {
                    measured == null -> "no preceding visible_text"
                    measured.result.verdict != Verdict.PASSED -> "the preceding visible_text failed"
                    else -> "the preceding visible_text had no event time (t0)"
                }
            return result(spec, Verdict.FAILED, observed = null, note = "no latency measured: $reason")
        }
        val max = AssertionText.ms(spec.max)
        val latency = AssertionText.ms(range.measured)
        val (verdict, note) =
            when {
                range.high <= spec.max -> {
                    Verdict.PASSED to null
                }

                range.low > spec.max -> {
                    Verdict.FAILED to "$latency exceeds $max"
                }

                range.measured > spec.max -> {
                    Verdict.INCONCLUSIVE to "$latency exceeds $max, but it is only an upper bound (${range.upperBoundBecause ?: UNBOUNDED})"
                }

                else -> {
                    Verdict.INCONCLUSIVE to "$latency is within $max, but the delay may be up to ${AssertionText.ms(range.high)} " +
                        "(${range.lowerBoundBecause ?: UNBOUNDED}), so the limit cannot be confirmed"
                }
            }
        return result(spec, verdict, observed = latency, note = note)
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
            note = if (passed) null else "target answered ${response.status}, expected ${spec.equals}${sentWith(response)}",
            rawEvidence = "${response.status} $body",
        )
    }

    /**
     * How a failed probe was signed: with the credential headers the tester's page had sent the target itself (a token
     * the page keeps), or with the session's cookies alone, which a site that signs its calls with a token refuses.
     */
    private fun sentWith(response: HttpProbeResult): String =
        when {
            response.credentials.isNotEmpty() -> "; sent with the page's own ${response.credentials.sorted().joinToString()} header"
            response.status == UNAUTHORIZED -> "; only the session's cookies went: the page had sent the target no token of its own"
            else -> ""
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
            // An object no event carried (the emitter failed, or no id came back): the check cannot be made, which says
            // nothing about the site; any other template error is the scenario's and fails.
            if (e.missingObject) {
                result(
                    spec,
                    Verdict.INCONCLUSIVE,
                    expected = describeBestEffort(spec, input),
                    observed = null,
                    note = "$ID_UNAVAILABLE: ${e.message}",
                )
            } else {
                result(
                    spec,
                    Verdict.FAILED,
                    expected = describeBestEffort(spec, input),
                    observed = null,
                    note = "template error: ${e.message}",
                )
            }
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
        /**
         * Leads the note of a `visible_text` whose receiver saw the text before the change was written (Faza 24.10): the
         * check proves nothing about this delivery.
         */
        const val STALE_TEXT = "stale_text"

        /** Leads the note of a check whose object no event carried: nothing to check it on. */
        const val ID_UNAVAILABLE = "id_unavailable"

        /** Why a latency is only a bound, when no reason was recorded. */
        const val UNBOUNDED = "only a bound of the delay is known"

        /** The note of an oracle check on a target without a test API (Faza 10). */
        const val NO_ORACLE = "N/A (no oracle)"

        /** The answer of a site to a call it could not tell who sent. */
        const val UNAUTHORIZED = 401

        /**
         * Shortest window handed to the browser. An adapter may round a timeout down to whole milliseconds and
         * Playwright reads 0 ms as "no timeout", so a sub-millisecond remainder becomes a single check, never a wait.
         */
        val MIN_WAIT = 1.milliseconds

        const val MAX_PATH_IN_NOTE = 200
    }
}
