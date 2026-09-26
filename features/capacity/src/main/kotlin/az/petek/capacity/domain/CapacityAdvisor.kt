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

package az.petek.capacity.domain

import kotlin.math.floor

/**
 * Recommends how many testers a machine can run at once. Pure: the same inputs always give the same advice.
 *
 * - **Memory.** A reserve of `max(2 GiB, 15 % of total memory)` stays free for the system and other programs. Into
 *   the rest (`available − reserve`) go the testers: n testers cost `n × bytesPerSession` plus one
 *   `bytesPerBrowser` for every started browser, `ceil(n / contextsPerBrowser)`. That is the amortized formula
 *   `(available − reserve) / (bytesPerSession + bytesPerBrowser / contextsPerBrowser)`, except that browsers are
 *   counted whole, which matters for small machines (one tester still needs a whole browser).
 * - **CPU.** `cpuCores × sessionsPerCore` (6 by default): agents spend most of their time waiting for the LLM and the
 *   network, so a core drives several of them.
 *
 * The recommendation is the smaller bound, at least 1 (one tester always runs). It is advice only; LLM throughput
 * (`PETEK_LLM_CONCURRENCY`, the AI provider's rate limits) is a separate limit on speed, not on the number of testers.
 */
class CapacityAdvisor(
    private val sessionsPerCore: Int = DEFAULT_SESSIONS_PER_CORE,
) {
    init {
        require(sessionsPerCore >= 1) { "sessionsPerCore must be at least 1, was $sessionsPerCore" }
    }

    /** [contextsPerBrowser] is the engine's sessions per shared browser; pass 1 when every session has its own browser. */
    fun recommend(
        resources: HostResources,
        cost: SessionCost,
        contextsPerBrowser: Int,
    ): CapacityRecommendation {
        require(contextsPerBrowser >= 1) { "contextsPerBrowser must be at least 1, was $contextsPerBrowser" }
        val reserve = reserveFor(resources.totalMemoryBytes)
        val usable = resources.availableMemoryBytes - reserve
        val memoryBound = testersFitting(usable, cost, contextsPerBrowser)
        val cpuBound = saturatedInt(resources.cpuCores.toLong() * sessionsPerCore)
        val limiting = if (memoryBound <= cpuBound) LimitingFactor.MEMORY else LimitingFactor.CPU
        return CapacityRecommendation(
            maxTesters = minOf(memoryBound, cpuBound).coerceAtLeast(1),
            limitingFactor = limiting,
            reserveBytes = reserve,
            perSession = cost,
            notes = notes(resources, cost, contextsPerBrowser, reserve, memoryBound, cpuBound, limiting),
            host = resources,
            memoryBound = memoryBound,
            cpuBound = cpuBound,
            contextsPerBrowser = contextsPerBrowser,
        )
    }

    private fun reserveFor(totalBytes: Long): Long = maxOf(MIN_RESERVE_BYTES, (totalBytes * RESERVE_SHARE).toLong())

    /** The largest n with `n × session + ceil(n / contexts) × browser <= usable`. */
    private fun testersFitting(
        usable: Long,
        cost: SessionCost,
        contexts: Int,
    ): Int {
        if (usable <= 0) return 0
        val amortized = cost.bytesPerSession + cost.bytesPerBrowser.toDouble() / contexts
        // The amortized estimate is never below the exact answer; step down until the whole browsers fit too.
        var testers = saturatedInt(floor(usable / amortized).toLong())
        while (testers > 0 && footprint(testers, cost, contexts) > usable) testers--
        return testers
    }

    private fun footprint(
        testers: Int,
        cost: SessionCost,
        contexts: Int,
    ): Double {
        val browsers = (testers.toLong() + contexts - 1) / contexts
        return testers.toDouble() * cost.bytesPerSession + browsers.toDouble() * cost.bytesPerBrowser
    }

    private fun notes(
        resources: HostResources,
        cost: SessionCost,
        contexts: Int,
        reserve: Long,
        memoryBound: Int,
        cpuBound: Int,
        limiting: LimitingFactor,
    ): List<String> =
        buildList {
            add("This is a recommendation, not a limit: Pətək starts as many testers as the campaign asks for.")
            add(
                "Memory: ${Bytes.format(resources.availableMemoryBytes)} available of ${Bytes.format(resources.totalMemoryBytes)}; " +
                    "${Bytes.format(reserve)} stays free for the system (the larger of 2 GiB and 15 % of the total), " +
                    "which leaves room for $memoryBound testers.",
            )
            if (memoryBound == 0) {
                add("Available memory is already below the reserve: close other programs before a large run.")
            }
            val source =
                if (cost.measured) "measured on this machine" else "estimates; `petek capacity --measure` measures this machine"
            add(
                "Per tester: ${Bytes.format(cost.bytesPerSession)} for its session plus ${Bytes.format(cost.bytesPerBrowser)} " +
                    "per browser shared by up to $contexts sessions ($source).",
            )
            add(
                "CPU: ${resources.cpuCores} cores × $sessionsPerCore sessions per core = $cpuBound testers " +
                    "(agents mostly wait for the LLM and the network).",
            )
            add("Limiting factor: ${limiting.name.lowercase()}.")
            add(
                "LLM throughput (PETEK_LLM_CONCURRENCY and your AI provider's rate limits) limits how fast the testers act, " +
                    "not how many can run.",
            )
        }

    private fun saturatedInt(value: Long): Int = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /** Sessions one core drives: agents mostly wait for the LLM and the network. */
        const val DEFAULT_SESSIONS_PER_CORE = 6

        /** The reserve is never smaller than this. */
        const val MIN_RESERVE_BYTES: Long = 2 * Bytes.GIB

        /** The reserve's share of total memory on large machines. */
        const val RESERVE_SHARE = 0.15
    }
}
