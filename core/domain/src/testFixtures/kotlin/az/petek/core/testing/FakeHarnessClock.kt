package az.petek.core.testing

import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** Manually advanced clock: wall time and monotonic time move together. */
class FakeHarnessClock(
    start: Instant = Instant.parse("2026-01-01T10:00:00Z"),
) : HarnessClock {
    private val nanos = AtomicLong(0)
    private val origin = start

    override fun now(): HarnessTimestamp {
        val n = nanos.get()
        return HarnessTimestamp(origin.plusNanos(n), n)
    }

    fun advance(by: Duration) {
        nanos.addAndGet(by.inWholeNanoseconds)
    }
}
