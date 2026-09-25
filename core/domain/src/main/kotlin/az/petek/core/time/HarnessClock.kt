package az.petek.core.time

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A point in time as measured by the harness (CLAUDE.md rule 1: time is never measured by the LLM).
 * [wall] is for records and reports; [monotonicNanos] is for latency maths inside one JVM.
 */
data class HarnessTimestamp(
    val wall: Instant,
    val monotonicNanos: Long,
) {
    fun elapsedUntil(later: HarnessTimestamp): Duration = (later.monotonicNanos - monotonicNanos).nanoseconds
}

interface HarnessClock {
    fun now(): HarnessTimestamp
}

class SystemHarnessClock : HarnessClock {
    override fun now(): HarnessTimestamp = HarnessTimestamp(Instant.now(), System.nanoTime())
}
