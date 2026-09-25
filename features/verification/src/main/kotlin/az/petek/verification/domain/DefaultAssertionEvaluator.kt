package az.petek.verification.domain

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.WaitOutcome
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.oracle.domain.JsonFieldSelector
import az.petek.oracle.domain.TargetOracle
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * The code-only judge of typed assertions (CLAUDE.md rule 2): nothing here asks an LLM, and every time is taken
 * from the harness [clock] (rule 1).
 *
 * Contract beyond [AssertionEvaluator]:
 * - It never throws for a failing target, browser, oracle or template: such problems become a FAILED result with a
 *   note, so one broken check cannot hide the others. Only coroutine cancellation propagates.
 * - Templates in texts, selectors, paths and expected values are rendered with [AssertionInput.templates] before
 *   use; `expected` shows the rendered values.
 * - `visible_text` measured against t0 waits only for what is left of `t0 + within`. When that window has already
 *   elapsed it checks once without waiting and reports the latency as an upper bound.
 * - Sources follow the three-source model: screen checks are RECEIVER, oracle and the target's own HTTP answers
 *   are ORACLE, `latency_max` is HARNESS and `only_one_succeeds` is SENDER.
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

                AssertionSpec.OnlyOneSucceeds -> {
                    result(spec, Verdict.SKIPPED, observed = null, note = "group-level: judged once per step over all actors")
                }
            }
        }
    }

    override fun evaluateOnlyOneSucceeds(results: List<ActorResult>): AssertionResult {
        val ordered = results.sortedBy { it.agentId.index }
        val winners = ordered.filter { it.succeeded }
        val winnerIds = winners.joinToString { it.agentId.value }
        val observed =
            when (winners.size) {
                0 -> "none of ${ordered.size} succeeded"
                1 -> "$winnerIds succeeded"
                else -> "${winners.size} succeeded: $winnerIds"
            }
        val note =
            when {
                ordered.isEmpty() -> "no actor results to compare"
                winners.isEmpty() -> "no actor succeeded; expected exactly one winner"
                winners.size > 1 -> "more than one actor succeeded ($winnerIds); expected exactly one"
                else -> null
            }
        return AssertionResult(
            spec = AssertionSpec.OnlyOneSucceeds,
            verdict = if (winners.size == 1) Verdict.PASSED else Verdict.FAILED,
            source = EvidenceSource.SENDER,
            expected = "exactly one of ${ordered.size} actors succeeds",
            observed = observed,
            latency = null,
            note = note,
            rawEvidence = actorResultsJson(ordered),
        )
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
                val waited = remaining.isPositive()
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
        val late = "the ${AssertionText.ms(within)} window had elapsed ${AssertionText.ms(-remaining)} before the check; checked once"
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
            return result(spec, Verdict.SKIPPED, expected = describeBestEffort(spec, input), observed = null, note = "no test API")
        }
        val rendered = spec.rendered(input)
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

    private fun AssertionSpec.VisibleText.rendered(input: AssertionInput) = copy(text = template(text, input))

    private fun AssertionSpec.NotVisible.rendered(input: AssertionInput) =
        copy(text = text?.let { template(it, input) }, selector = selector?.let { template(it, input) })

    private fun AssertionSpec.Count.rendered(input: AssertionInput) = copy(selector = template(selector, input))

    private fun AssertionSpec.Oracle.rendered(input: AssertionInput) =
        copy(
            path = template(path, input),
            equals = equals?.let { template(it, input) },
            contains = contains?.let { template(it, input) },
        )

    private fun AssertionSpec.HttpStatus.rendered(input: AssertionInput) =
        copy(path = template(path, input), method = method.trim().uppercase())

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
                    is AssertionSpec.LatencyMax, AssertionSpec.OnlyOneSucceeds -> spec
                }
            } catch (_: TemplateException) {
                spec
            }
        return AssertionText.describe(shown)
    }

    // --- failures and result building ---------------------------------------------------------------------------------

    /** Runs one check and turns any non-cancellation failure into a FAILED result with a note. */
    private inline fun guarded(
        spec: AssertionSpec,
        input: AssertionInput,
        check: () -> AssertionResult,
    ): AssertionResult =
        try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TemplateException) {
            result(spec, Verdict.FAILED, expected = describeBestEffort(spec, input), observed = null, note = "template error: ${e.message}")
        } catch (e: Exception) {
            result(
                spec,
                Verdict.FAILED,
                expected = describeBestEffort(spec, input),
                observed = null,
                note = "${spec.type} check failed: ${AssertionText.error(e)}",
            )
        }

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
            AssertionSpec.OnlyOneSucceeds -> EvidenceSource.SENDER
        }

    private fun actorResultsJson(results: List<ActorResult>): String =
        buildJsonObject {
            put("assertion", AssertionSpec.OnlyOneSucceeds.type)
            put("winners", results.count { it.succeeded })
            putJsonArray("winner_ids") { results.filter { it.succeeded }.forEach { add(it.agentId.value) } }
            putJsonArray("actors") {
                results.forEach { actor ->
                    addJsonObject {
                        put("agent_id", actor.agentId.value)
                        put("succeeded", actor.succeeded)
                        put("summary", actor.summary)
                    }
                }
            }
        }.toString()
}
