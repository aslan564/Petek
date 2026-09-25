/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.capacity.domain

import az.petek.core.error.PetekException
import java.net.URI

/**
 * What the machine offers right now. [availableMemoryBytes] is memory that can be used without swapping (Linux
 * `MemAvailable`, lowered to the headroom of a container's memory limit); [cpuCores] are the cores this process may use.
 */
data class HostResources(
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val cpuCores: Int,
) {
    init {
        require(totalMemoryBytes > 0) { "totalMemoryBytes must be positive, was $totalMemoryBytes" }
        require(availableMemoryBytes >= 0) { "availableMemoryBytes must not be negative, was $availableMemoryBytes" }
        require(cpuCores >= 1) { "cpuCores must be at least 1, was $cpuCores" }
    }
}

/**
 * Memory one tester costs: [bytesPerSession] for its own browser session (the Playwright driver process each session
 * starts, the context's renderer and the session's share of the JVM) and [bytesPerBrowser] for every shared Chromium,
 * which hosts up to `contextsPerBrowser` sessions. [measured] tells whether the numbers were measured on this machine
 * ([SessionCostProbe]) or are the documented [ESTIMATE].
 */
data class SessionCost(
    val bytesPerSession: Long,
    val bytesPerBrowser: Long,
    val measured: Boolean,
) {
    init {
        require(bytesPerSession > 0) { "bytesPerSession must be positive, was $bytesPerSession" }
        require(bytesPerBrowser >= 0) { "bytesPerBrowser must not be negative, was $bytesPerBrowser" }
    }

    companion object {
        /**
         * Estimates used when nothing was measured, from headless Chromium on Linux: about 180 MiB per session
         * (Node.js driver of its Playwright instance, context renderer, JVM share) and 350 MiB per shared browser
         * (browser, GPU and network processes plus the browser server host). Pages heavier than a typical admin panel
         * cost more; `petek capacity --measure` replaces the estimate with this machine's numbers.
         */
        val ESTIMATE = SessionCost(bytesPerSession = 180 * Bytes.MIB, bytesPerBrowser = 350 * Bytes.MIB, measured = false)
    }
}

/** Which resource sets the recommended maximum. */
enum class LimitingFactor { MEMORY, CPU }

/**
 * Advice on how many testers this machine can run at once, never a limit: Pətək starts whatever the campaign asks
 * for, and callers only warn when a campaign asks for more than [maxTesters].
 *
 * @property maxTesters the smaller of [memoryBound] and [cpuBound], at least 1.
 * @property reserveBytes memory kept free for the operating system and other programs.
 * @property perSession the cost the memory bound was computed with.
 * @property notes human-readable explanations, always including that LLM throughput limits speed, not tester count.
 */
data class CapacityRecommendation(
    val maxTesters: Int,
    val limitingFactor: LimitingFactor,
    val reserveBytes: Long,
    val perSession: SessionCost,
    val notes: List<String>,
    val host: HostResources,
    /** Testers whose sessions and browsers fit into the available memory minus the reserve (may be 0). */
    val memoryBound: Int,
    /** Testers the CPU cores can drive. */
    val cpuBound: Int,
    /** Sessions per shared browser the memory bound assumed (1 for one browser per session). */
    val contextsPerBrowser: Int,
)

/** Port: reads the machine's memory and cores. Fast enough to call before every run. */
fun interface HostResourceProbe {
    suspend fun probe(): HostResources
}

/**
 * Port: measures [SessionCost] by opening [sessions] real browser sessions on [url] (a blank page when null) and
 * observing how much memory the browser processes grow by. Takes a few seconds and starts browsers, so it only runs
 * when the user asks for a measurement. Fails with [CapacityMeasurementException] when measuring is not possible.
 */
fun interface SessionCostProbe {
    suspend fun measure(
        sessions: Int,
        url: URI?,
    ): SessionCost
}

/** Memory could not be measured (no `/proc`, no browser, ...); the estimate still applies. */
class CapacityMeasurementException(
    message: String,
    cause: Throwable? = null,
) : PetekException(message, cause)
