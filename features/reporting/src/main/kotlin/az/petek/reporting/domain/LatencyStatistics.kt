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

package az.petek.reporting.domain

import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import kotlin.math.roundToLong

/**
 * Real-time delivery statistics per emitted event (docs/PLAN.md "Real-time"): every latency is t1 − t0 as measured
 * by the harness (AGENTS.md rule 1); this only aggregates. A receiver whose receipt says `received = false` is
 * missing; a received receipt without a latency counts as received but stays out of avg/p95/max.
 */
object LatencyStatistics {
    /** One entry per event in [events] order; receipts of unknown events are ignored. */
    fun compute(
        events: List<EventRecord>,
        receipts: List<EventReceipt>,
    ): List<LatencyStats> {
        val receiptsByEvent = receipts.groupBy { it.eventId }
        val labels = labels(events)
        return events.mapIndexed { i, event -> stats(labels[i], receiptsByEvent[event.eventId].orEmpty()) }
    }

    /** Nearest-rank percentile of [values] (any order): the smallest value with at least [percentile]% at or below it. */
    fun nearestRank(
        values: List<Long>,
        percentile: Int,
    ): Long? {
        require(percentile in 1..100) { "Percentile must be in 1..100, was $percentile" }
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        // rank = ceil(percentile / 100 × n), in integers so no floating-point error can shift it.
        val rank = ((percentile * sorted.size + 99) / 100).coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun stats(
        label: String,
        receipts: List<EventReceipt>,
    ): LatencyStats {
        // A receiver may be recorded twice (e.g. a retry); seeing the event once is what counts.
        val perReceiver =
            receipts
                .groupBy { it.receiver }
                .mapValues { (_, own) -> own.firstOrNull { it.received } ?: own.last() }
                .toSortedMap()
        val received = perReceiver.values.filter { it.received }
        val latencies = received.mapNotNull { it.latencyMs }
        return LatencyStats(
            event = label,
            receivers = perReceiver.size,
            received = received.size,
            missing = perReceiver.values.filterNot { it.received }.map { it.receiver.value },
            avgMs = latencies.takeIf { it.isNotEmpty() }?.average()?.roundToLong(),
            p95Ms = nearestRank(latencies, P95),
            maxMs = latencies.maxOrNull(),
            perReceiverMs =
                perReceiver.entries.associate { (agent, receipt) ->
                    agent.value to receipt.latencyMs.takeIf { receipt.received }
                },
        )
    }

    /** `name #objectId` identifies an event for a reader; repeated labels get an ordinal so rows stay distinct. */
    private fun labels(events: List<EventRecord>): List<String> {
        val base = events.map { event -> event.objectId?.let { "${event.name} #$it" } ?: event.name }
        val seen = mutableMapOf<String, Int>()
        return base.map { label ->
            val n = (seen[label] ?: 0) + 1
            seen[label] = n
            if (n == 1) label else "$label ($n)"
        }
    }

    private const val P95 = 95
}
