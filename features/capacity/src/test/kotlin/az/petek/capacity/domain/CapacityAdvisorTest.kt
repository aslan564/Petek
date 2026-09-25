package az.petek.capacity.domain

import az.petek.capacity.domain.Bytes.GIB
import az.petek.capacity.domain.Bytes.MIB
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class CapacityAdvisorTest {
    private val advisor = CapacityAdvisor()
    private val estimate = SessionCost.ESTIMATE

    private fun host(
        totalGib: Long,
        available: Long,
        cores: Int,
    ) = HostResources(totalMemoryBytes = totalGib * GIB, availableMemoryBytes = available, cpuCores = cores)

    @Test
    fun `a machine short of memory is memory-bound`() {
        val advice = advisor.recommend(host(16, 10 * GIB, cores = 16), estimate, contextsPerBrowser = 20)

        // Reserve 15 % of 16 GiB = 2.4 GiB; 7.6 GiB left = 39 sessions of 180 MiB + 2 browsers of 350 MiB.
        advice.reserveBytes shouldBe 2_576_980_377
        advice.memoryBound shouldBe 39
        advice.cpuBound shouldBe 96
        advice.maxTesters shouldBe 39
        advice.limitingFactor shouldBe LimitingFactor.MEMORY
        advice.perSession shouldBe estimate
        advice.contextsPerBrowser shouldBe 20
    }

    @Test
    fun `a machine with plenty of memory and few cores is CPU-bound`() {
        val advice = advisor.recommend(host(64, 40 * GIB, cores = 16), estimate, contextsPerBrowser = 20)

        advice.memoryBound shouldBe 157
        advice.cpuBound shouldBe 96
        advice.maxTesters shouldBe 96
        advice.limitingFactor shouldBe LimitingFactor.CPU
    }

    @Test
    fun `more cores make the same machine memory-bound`() {
        val advice = advisor.recommend(host(64, 40 * GIB, cores = 32), estimate, contextsPerBrowser = 20)

        advice.maxTesters shouldBe 157
        advice.limitingFactor shouldBe LimitingFactor.MEMORY
    }

    @Test
    fun `a strong machine is advised hundreds of testers`() {
        val advice = advisor.recommend(host(512, 480 * GIB, cores = 128), estimate, contextsPerBrowser = 20)

        advice.maxTesters shouldBe 768
        advice.limitingFactor shouldBe LimitingFactor.CPU
        advice.memoryBound shouldBe 2_089
    }

    @Test
    fun `the reserve is at least 2 GiB on small machines and 15 percent on large ones`() {
        advisor.recommend(host(8, 6 * GIB, 4), estimate, 20).reserveBytes shouldBe 2 * GIB
        advisor.recommend(host(13, 6 * GIB, 4), estimate, 20).reserveBytes shouldBe 2 * GIB
        advisor.recommend(host(100, 6 * GIB, 4), estimate, 20).reserveBytes shouldBe (100 * GIB * 0.15).toLong()
    }

    @Test
    fun `browsers are counted whole, so one tester still needs a whole browser`() {
        // 530 MiB above the reserve: the amortized formula says 2 (530 / 197.5), but 2 sessions + 1 browser need 710 MiB.
        val advice = advisor.recommend(host(8, 2 * GIB + 530 * MIB, 8), estimate, contextsPerBrowser = 20)

        advice.memoryBound shouldBe 1
    }

    @Test
    fun `one browser per session costs a whole browser per tester`() {
        advisor.recommend(host(8, 2 * GIB + 1060 * MIB, 8), estimate, contextsPerBrowser = 1).memoryBound shouldBe 2
        advisor.recommend(host(8, 2 * GIB + 1059 * MIB, 8), estimate, contextsPerBrowser = 1).memoryBound shouldBe 1
    }

    @Test
    fun `at least one tester is always recommended, with a note when memory is below the reserve`() {
        val advice = advisor.recommend(host(4, 1 * GIB, cores = 2), estimate, contextsPerBrowser = 20)

        advice.memoryBound shouldBe 0
        advice.maxTesters shouldBe 1
        advice.limitingFactor shouldBe LimitingFactor.MEMORY
        advice.notes shouldContain "Available memory is already below the reserve: close other programs before a large run."
    }

    @Test
    fun `equal bounds count as memory-bound`() {
        // 12 sessions + 1 browser = 2510 MiB above the 2 GiB reserve; 2 cores x 6 = 12.
        val advice = advisor.recommend(host(8, 2 * GIB + 2510 * MIB, cores = 2), estimate, contextsPerBrowser = 20)

        advice.memoryBound shouldBe 12
        advice.cpuBound shouldBe 12
        advice.limitingFactor shouldBe LimitingFactor.MEMORY
    }

    @Test
    fun `sessions per core can be tuned`() {
        CapacityAdvisor(sessionsPerCore = 2).recommend(host(64, 40 * GIB, 16), estimate, 20).cpuBound shouldBe 32
    }

    @Test
    fun `a measured cost is used as given`() {
        val measured = SessionCost(bytesPerSession = 90 * MIB, bytesPerBrowser = 200 * MIB, measured = true)

        val advice = advisor.recommend(host(16, 10 * GIB, cores = 64), measured, contextsPerBrowser = 20)

        advice.perSession shouldBe measured
        advice.memoryBound shouldBe 77
        advice.notes.joinToString("\n") shouldContain "(measured on this machine)"
    }

    @Test
    fun `the notes explain the numbers and always say that LLM throughput limits speed, not testers`() {
        val notes = advisor.recommend(host(16, 10 * GIB, cores = 16), estimate, 20).notes

        notes.first() shouldBe "This is a recommendation, not a limit: Pətək starts as many testers as the campaign asks for."
        val text = notes.joinToString("\n")
        text shouldContain "Memory: 10 GiB available of 16 GiB; 2.4 GiB stays free for the system"
        text shouldContain "which leaves room for 39 testers"
        text shouldContain "Per tester: 180 MiB for its session plus 350 MiB per browser shared by up to 20 sessions"
        text shouldContain "`petek capacity --measure` measures this machine"
        text shouldContain "CPU: 16 cores × 6 sessions per core = 96 testers"
        text shouldContain "Limiting factor: memory."
        notes.last() shouldBe
            "LLM throughput (PETEK_LLM_CONCURRENCY and your Claude plan's rate limits) limits how fast the testers act, " +
            "not how many can run."
    }

    @Test
    fun `invalid inputs are rejected`() {
        shouldThrow<IllegalArgumentException> { advisor.recommend(host(8, GIB, 1), estimate, contextsPerBrowser = 0) }
        shouldThrow<IllegalArgumentException> { CapacityAdvisor(sessionsPerCore = 0) }
        shouldThrow<IllegalArgumentException> { HostResources(0, 0, 1) }
        shouldThrow<IllegalArgumentException> { HostResources(GIB, -1, 1) }
        shouldThrow<IllegalArgumentException> { HostResources(GIB, GIB, 0) }
        shouldThrow<IllegalArgumentException> { SessionCost(0, 0, measured = true) }
        shouldThrow<IllegalArgumentException> { SessionCost(MIB, -1, measured = true) }
    }

    @Test
    fun `the estimate is 180 MiB per session and 350 MiB per browser`() {
        estimate shouldBe SessionCost(180 * MIB, 350 * MIB, measured = false)
    }

    @Test
    fun `byte sizes read naturally`() {
        Bytes.format(512) shouldBe "512 B"
        Bytes.format(2 * Bytes.KIB) shouldBe "2 KiB"
        Bytes.format(180 * MIB) shouldBe "180 MiB"
        Bytes.format(1536 * MIB) shouldBe "1.5 GiB"
        Bytes.format(2_576_980_377) shouldBe "2.4 GiB"
        Bytes.format(16 * GIB) shouldBe "16 GiB"
    }
}
