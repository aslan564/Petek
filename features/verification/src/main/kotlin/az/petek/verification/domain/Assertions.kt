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
import az.petek.browser.domain.TextWatch
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.TemplateContext
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import java.net.URI
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
    /** When the awaited event's change reached the target (t0); `visible_text` latency is measured from here. */
    val eventTime: EventTime?,
    /** What the receiver's page saw while it watched for a `visible_text` before the change was written (Faza 24.10). */
    val watch: WatchedText? = null,
    /**
     * Why the awaited event has no delivery to time here: it was published in an earlier execution of the steps (a
     * setup event of the first wave read in a later wave, or in the account swap). `visible_text` then only checks
     * that the text is shown, and `latency_max` does not apply; [eventTime] would time a delivery long past.
     */
    val earlierDelivery: String? = null,
    /**
     * The site's API host when the campaign's `api_prefix` is a full address there (2026-09-30): the one origin other
     * than the target an `http_status` check may call. Null: only the target.
     */
    val apiOrigin: URI? = null,
)

/**
 * When the change a receiver waits for reached the target (docs/adr/0006). The latency t1 − t0 is measured from [t0];
 * the write itself happened between [earliest] and [latest], one instant when the emitter's page showed the request
 * that made it. [source] says what t0 is, for the evidence.
 */
data class EventTime(
    val t0: HarnessTimestamp,
    val earliest: HarnessTimestamp,
    val latest: HarnessTimestamp,
    val source: String,
) {
    /** The write's moment is known, not only a window around it. */
    val exact: Boolean get() = earliest.monotonicNanos == latest.monotonicNanos

    companion object {
        /** A change whose write time is known exactly. */
        fun at(
            t0: HarnessTimestamp,
            source: String = "the event",
        ): EventTime = EventTime(t0, t0, t0, source)
    }
}

/** A receiver's watch for [text] (rendered as it was when the watch began) and what it saw by the time it was read. */
data class WatchedText(
    val text: String,
    val reading: TextWatch,
)

/** Result of one typed check. Evaluated by code only (AGENTS.md rule 2). */
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
    /**
     * The target's test API answer behind part of a verdict whose [rawEvidence] is something else (the oracle
     * condition of `only_one_succeeds`); kept as an ORACLE artifact.
     */
    val oracleEvidence: String? = null,
)

/**
 * Outcome of one actor in a parallel step, for `only_one_succeeds`. [succeeded] is decided by code from the actor's
 * own requests ([race], see [RaceEvidence]), never by the agent; [summary] is the agent's text, kept as evidence only.
 */
data class ActorResult(
    val agentId: AgentId,
    val succeeded: Boolean,
    val summary: String,
    /** The requests [succeeded] was decided from; null when the action never ran (awaited event missing, template error). */
    val race: RaceEvidence? = null,
    /** The actor lost the race: refused because the object was already decided (an expected outcome, not a failure). */
    val lostRace: Boolean = false,
    /** The actor's page, for the evidence of a group check that failed (every racer's screen as the race left it). */
    val session: BrowserSession? = null,
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

    /**
     * `only_one_succeeds` as [spec] asks: exactly one of [results] succeeded and, when [spec] names an oracle
     * condition and the test API is available, the target's final state matches it ([input] renders its templates).
     * Implementations without an oracle keep the default, which judges the results alone.
     */
    suspend fun evaluateOnlyOneSucceeds(
        spec: AssertionSpec.OnlyOneSucceeds,
        results: List<ActorResult>,
        input: AssertionInput,
    ): AssertionResult = evaluateOnlyOneSucceeds(results)
}
