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

package az.petek.reporting.domain.visual

import az.petek.core.ids.AgentId
import az.petek.evidence.domain.PageLookRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult

/** One run's page looks: a compared run's, or one of its `--repeat` siblings'. */
data class LookRunSamples(
    val run: RunRecord,
    val looks: List<PageLookRecord>,
)

/** A side's captures of one look: the [representative] compared with the other release, and its [peers]. */
data class SideSamples(
    val representative: PageLookRecord,
    val peers: List<PageLookRecord> = emptyList(),
) {
    val all: List<PageLookRecord> get() = listOf(representative) + peers
}

/**
 * What the comparison does with one look. A [decided] look needs no picture (one run did not look, or a side met only
 * the surroundings); otherwise [before] is compared with [after], unless [mismatch] (another browser or screen) makes
 * it not comparable once the frames are verified.
 */
data class LookPlan(
    val index: Int,
    val key: LookKey,
    val before: SideSamples?,
    val after: SideSamples?,
    val decided: LookChange? = null,
    val reason: LookReason? = null,
    val mismatch: LookReason? = null,
)

/**
 * Pairs the looks of two runs (docs/adr/0014), by [LookKey]: step, page and screen, never the tester.
 *
 * - **Samples.** A side's captures of a key are its own run's and, after them, those of the finished runs of its
 *   `--repeat` group with the same scenario file (same campaign hash), oldest first, never the other side's run; at most
 *   [VisualThresholds.maxSamples]. A capture whose page answered 429 or 503 is the surroundings and never a sample.
 * - **Representative.** The lowest tester id that looked in both compared runs, otherwise the side's lowest; the
 *   earliest capture on a tie. Peers are the other samples taken with the same renderer and screen.
 * - **Missing looks.** A key only one run has is [LookChange.ADDED] or [LookChange.REMOVED] when the scenario added or
 *   removed its step, otherwise not comparable ([LookReason.MISSING_BEFORE], [LookReason.MISSING_NOW]): never a change.
 * - A side whose every capture met the surroundings is [LookReason.SURROUNDINGS].
 */
object LookPairing {
    /** Statuses of the surroundings (rate limiting, an unavailable service), never of the page itself. */
    val SURROUNDINGS: Set<Int> = setOf(429, 503)

    /**
     * [baseline] and [current] start with the compared run; the rest are its repeat siblings. [added] and [removed]
     * are the scenario steps the current scenario added and removed.
     */
    fun plan(
        baseline: List<LookRunSamples>,
        current: List<LookRunSamples>,
        added: Set<String>,
        removed: Set<String>,
        maxSamples: Int,
    ): List<LookPlan> {
        require(baseline.isNotEmpty() && current.isNotEmpty()) { "each side needs its compared run" }
        val before = Side(baseline, current.first().run)
        val after = Side(current, baseline.first().run)
        val keys =
            LinkedHashSet<LookKey>().apply {
                current.first().looks.mapTo(this, ::keyOf)
                baseline.first().looks.mapTo(this, ::keyOf)
            }
        return keys.mapIndexed { i, key ->
            val index = i + 1
            val then = before.own(key)
            val now = after.own(key)
            when {
                then.isEmpty() -> {
                    val side = after.samples(key, emptySet(), maxSamples)
                    if (key.scenarioStep in added) {
                        LookPlan(index, key, null, side, LookChange.ADDED)
                    } else {
                        LookPlan(index, key, null, side, LookChange.NOT_COMPARABLE, LookReason.MISSING_BEFORE)
                    }
                }

                now.isEmpty() -> {
                    val side = before.samples(key, emptySet(), maxSamples)
                    if (key.scenarioStep in removed) {
                        LookPlan(index, key, side, null, LookChange.REMOVED)
                    } else {
                        LookPlan(index, key, side, null, LookChange.NOT_COMPARABLE, LookReason.MISSING_NOW)
                    }
                }

                else -> {
                    val bothBefore = then.filter(::usable).mapTo(HashSet()) { it.agentId }
                    val bothAfter = now.filter(::usable).mapTo(HashSet()) { it.agentId }
                    val b = before.samples(key, bothAfter, maxSamples)
                    val a = after.samples(key, bothBefore, maxSamples)
                    when {
                        b == null || a == null -> LookPlan(index, key, b, a, LookChange.NOT_COMPARABLE, LookReason.SURROUNDINGS)
                        else -> LookPlan(index, key, b, a, mismatch = mismatch(b.representative, a.representative))
                    }
                }
            }
        }
    }

    fun keyOf(record: PageLookRecord): LookKey = LookKey(record.scenarioStep, record.page, record.device)

    /** A capture of the page itself, not of the surroundings. */
    fun usable(record: PageLookRecord): Boolean = record.status !in SURROUNDINGS

    /** Another browser or system, or another screen size: the two captures are not comparable. */
    fun mismatch(
        before: PageLookRecord,
        after: PageLookRecord,
    ): LookReason? =
        when {
            before.renderer != after.renderer -> LookReason.OTHER_BROWSER
            before.viewportWidth != after.viewportWidth || before.viewportHeight != after.viewportHeight -> LookReason.OTHER_SCREEN
            else -> null
        }

    /** One side: its compared run and the usable repeat siblings, oldest first. */
    private class Side(
        runs: List<LookRunSamples>,
        other: RunRecord,
    ) {
        private val compared = runs.first()
        private val siblings =
            runs
                .drop(1)
                .filter { sibling ->
                    val run = sibling.run
                    run.runId != compared.run.runId && run.runId != other.runId && run.result != RunResult.RUNNING &&
                        run.campaignHash == compared.run.campaignHash && run.repeatGroup != null &&
                        run.repeatGroup == compared.run.repeatGroup
                }.distinctBy { it.run.runId }
                .sortedBy { it.run.startedAt }

        fun own(key: LookKey): List<PageLookRecord> = compared.looks.filter { keyOf(it) == key }

        /** The representative (preferring a tester in [both]) and its peers, or null when no capture is usable. */
        fun samples(
            key: LookKey,
            both: Set<AgentId>,
            maxSamples: Int,
        ): SideSamples? {
            val own = own(key).filter(::usable).sortedWith(compareBy({ it.agentId }, { it.recordedAt }))
            val others =
                siblings.flatMap { sibling ->
                    sibling.looks.filter { keyOf(it) == key && usable(it) }.sortedWith(compareBy({ it.agentId }, { it.recordedAt }))
                }
            val pool = own.ifEmpty { others }
            val representative =
                pool.minWithOrNull(compareBy({ it.agentId !in both }, { it.agentId }, { it.recordedAt })) ?: return null
            val peers =
                (own + others)
                    .filter { it !== representative && mismatch(representative, it) == null }
                    .take(maxSamples - 1)
            return SideSamples(representative, peers)
        }
    }
}
