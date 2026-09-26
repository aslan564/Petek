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
