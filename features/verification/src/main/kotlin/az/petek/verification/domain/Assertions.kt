package az.petek.verification.domain

import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.TemplateContext
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import kotlin.time.Duration

/** What an assertion about one actor's step can look at. */
data class AssertionInput(
    val runId: RunId,
    val stepId: StepId,
    val scenarioStep: String,
    val agentId: AgentId?,
    /** The actor's own browser session (receiver view "B"). */
    val session: BrowserSession?,
    val templates: TemplateContext,
    /** Harness time the awaited event was emitted (t0); visible_text latency is measured from here. */
    val eventEmittedAt: HarnessTimestamp?,
)

/** Result of one typed check. Evaluated by code only (CLAUDE.md rule 2). */
data class AssertionResult(
    val spec: AssertionSpec,
    val verdict: Verdict,
    val source: EvidenceSource,
    val expected: String,
    val observed: String?,
    /** t1 − t0 for visible_text; null otherwise. */
    val latency: Duration?,
    val note: String?,
    /** Raw oracle/HTTP body to keep as evidence, if any. */
    val rawEvidence: String? = null,
)

/** Outcome of one actor in a parallel step, for `only_one_succeeds`. */
data class ActorResult(
    val agentId: AgentId,
    val succeeded: Boolean,
    val summary: String,
)

/**
 * Evaluates a list of per-actor assertions in order. `latency_max` refers to the latency measured by the
 * preceding `visible_text` in the same list. Oracle assertions are SKIPPED (with a note) when the oracle is unavailable.
 */
interface AssertionEvaluator {
    suspend fun evaluate(
        specs: List<AssertionSpec>,
        input: AssertionInput,
    ): List<AssertionResult>

    /** `only_one_succeeds`: exactly one of [results] succeeded. */
    fun evaluateOnlyOneSucceeds(results: List<ActorResult>): AssertionResult
}
