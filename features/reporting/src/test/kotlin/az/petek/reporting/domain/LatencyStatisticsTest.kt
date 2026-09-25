package az.petek.reporting.domain

import az.petek.reporting.ReportTestData.event
import az.petek.reporting.ReportTestData.receipt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LatencyStatisticsTest {
    @Test
    fun `average p95 and max are computed over the receivers that saw the event`() {
        // 20 receivers a02..a21 with latencies 100, 200, ..., 2000 ms.
        val receipts = (1..20).map { receipt("evt_1", "a" + (it + 1).toString().padStart(2, '0'), it * 100L) }

        val stats = LatencyStatistics.compute(listOf(event("evt_1")), receipts).single()

        stats.receivers shouldBe 20
        stats.received shouldBe 20
        stats.missing.shouldBeEmpty()
        stats.avgMs shouldBe 1050
        stats.p95Ms shouldBe 1900
        stats.maxMs shouldBe 2000
    }

    @Test
    fun `receivers that never saw the event are missing and have no latency`() {
        val receipts =
            listOf(
                receipt("evt_1", "a10", null, received = false),
                receipt("evt_1", "a02", 800),
                receipt("evt_1", "a03", null, received = false),
                receipt("evt_1", "a04", 1200),
            )

        val stats = LatencyStatistics.compute(listOf(event("evt_1")), receipts).single()

        stats.receivers shouldBe 4
        stats.received shouldBe 2
        stats.missing shouldContainExactly listOf("a03", "a10")
        stats.avgMs shouldBe 1000
        stats.maxMs shouldBe 1200
        stats.perReceiverMs shouldContainExactly mapOf("a02" to 800L, "a03" to null, "a04" to 1200L, "a10" to null)
        stats.perReceiverMs.keys.toList() shouldContainExactly listOf("a02", "a03", "a04", "a10")
    }

    @Test
    fun `a receipt that saw the event without a measured latency counts as received only`() {
        val receipts = listOf(receipt("evt_1", "a02", 500), receipt("evt_1", "a03", null, received = true))

        val stats = LatencyStatistics.compute(listOf(event("evt_1")), receipts).single()

        stats.received shouldBe 2
        stats.missing.shouldBeEmpty()
        stats.avgMs shouldBe 500
        stats.perReceiverMs["a03"].shouldBeNull()
    }

    @Test
    fun `an event nobody waited for has empty statistics`() {
        val stats = LatencyStatistics.compute(listOf(event("evt_1", name = "ticket_created", objectId = null)), emptyList()).single()

        stats shouldBe
            LatencyStats(
                event = "ticket_created",
                receivers = 0,
                received = 0,
                missing = emptyList(),
                avgMs = null,
                p95Ms = null,
                maxMs = null,
                perReceiverMs = emptyMap(),
            )
    }

    @Test
    fun `a receiver recorded twice counts once and seeing the event wins`() {
        val receipts = listOf(receipt("evt_1", "a02", null, received = false), receipt("evt_1", "a02", 900))

        val stats = LatencyStatistics.compute(listOf(event("evt_1")), receipts).single()

        stats.receivers shouldBe 1
        stats.received shouldBe 1
        stats.missing.shouldBeEmpty()
        stats.avgMs shouldBe 900
    }

    @Test
    fun `the average is rounded to whole milliseconds`() {
        val receipts = listOf(receipt("evt_1", "a02", 100), receipt("evt_1", "a03", 101))

        LatencyStatistics.compute(listOf(event("evt_1")), receipts).single().avgMs shouldBe 101
    }

    @Test
    fun `events are labelled by name and object id and kept apart`() {
        val events =
            listOf(
                event("evt_1", "announcement_created", "42"),
                event("evt_2", "announcement_created", "43"),
                event("evt_3", "announcement_created", "42"),
                event("evt_4", "ticket_created", null),
            )
        val receipts = listOf(receipt("evt_2", "a02", 300), receipt("evt_9", "a02", 1))

        val stats = LatencyStatistics.compute(events, receipts)

        stats.map { it.event } shouldContainExactly
            listOf("announcement_created #42", "announcement_created #43", "announcement_created #42 (2)", "ticket_created")
        stats.map { it.received } shouldContainExactly listOf(0, 1, 0, 0)
    }

    @Test
    fun `nearest rank percentile follows its definition`() {
        LatencyStatistics.nearestRank(listOf(15, 20, 35, 40, 50), 30) shouldBe 20
        LatencyStatistics.nearestRank(listOf(15, 20, 35, 40, 50), 40) shouldBe 20
        LatencyStatistics.nearestRank(listOf(15, 20, 35, 40, 50), 50) shouldBe 35
        LatencyStatistics.nearestRank(listOf(50, 15, 40, 20, 35), 100) shouldBe 50
        LatencyStatistics.nearestRank(listOf(15, 20, 35, 40, 50), 1) shouldBe 15
        LatencyStatistics.nearestRank(listOf(7), 95) shouldBe 7
        LatencyStatistics.nearestRank(emptyList(), 95).shouldBeNull()
    }

    @Test
    fun `a percentile outside 1 to 100 is rejected`() {
        shouldThrow<IllegalArgumentException> { LatencyStatistics.nearestRank(listOf(1), 0) }
        shouldThrow<IllegalArgumentException> { LatencyStatistics.nearestRank(listOf(1), 101) }
    }
}
