package az.petek.capacity.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.BrowserTopology
import az.petek.browser.domain.SessionOptions
import az.petek.browser.testing.FakeBrowserSession
import az.petek.capacity.domain.Bytes.MIB
import az.petek.capacity.domain.SessionCost
import az.petek.capacity.infrastructure.BrowserSessionCostProbe.Companion.JVM_SHARE_PER_SESSION
import az.petek.capacity.infrastructure.BrowserSessionCostProbe.Companion.MIN_SESSION_BYTES
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionCostProbeTest {
    /** A browser engine whose processes use memory like real ones: some per started engine, some per open session. */
    private class FakeEngine(
        private val failOpening: Int? = null,
    ) : BrowserEngine {
        val events = CopyOnWriteArrayList<String>()
        val configs = CopyOnWriteArrayList<BrowserEngineConfig>()
        val sessions = CopyOnWriteArrayList<FakeBrowserSession>()
        val opened = AtomicInteger()
        var started: BrowserEngineConfig? = null

        override suspend fun start(config: BrowserEngineConfig): BrowserSessionFactory {
            started = config
            configs += config
            events += "start contextsPerBrowser=${config.contextsPerBrowser}"
            return BrowserSessionFactory { options -> open(options) }
        }

        private fun open(options: SessionOptions): FakeBrowserSession {
            if (opened.incrementAndGet() == failOpening) throw BrowserActionException("could not open browser session '${options.label}'")
            events += "open ${options.label} base=${options.baseUrl}"
            return FakeBrowserSession(options.label).also { sessions += it }
        }

        override suspend fun stop() {
            events += "stop"
            started = null
        }

        fun openSessions(): Int = sessions.count { !it.closed }
    }

    private val engine = FakeEngine()

    /** 1 GiB of unrelated children, 300 MiB per running browser, 150 MiB per open session. */
    private val memory =
        ProcessMemory {
            1024 * MIB + (if (engine.started != null) 300 * MIB else 0) + engine.openSessions() * 150 * MIB
        }

    private fun probe(
        config: BrowserEngineConfig = BrowserEngineConfig(),
        target: FakeEngine = engine,
        processMemory: ProcessMemory = memory,
    ) = BrowserSessionCostProbe(target, config, processMemory, BrowserSessionCostProbe.Timing(2.seconds, 3, 500.milliseconds))

    @Test
    fun `the cost per session and per browser comes from the memory growth of the browser processes`() =
        runTest {
            val cost = probe().measure(sessions = 5, url = null)

            cost shouldBe SessionCost(bytesPerSession = 150 * MIB + JVM_SHARE_PER_SESSION, bytesPerBrowser = 300 * MIB, measured = true)
        }

    @Test
    fun `sessions open a blank page by default and the given page otherwise`() =
        runTest {
            probe().measure(sessions = 2, url = null)
            engine.sessions.map { it.actions.toList() } shouldContainExactly
                listOf(listOf("navigate about:blank"), listOf("navigate about:blank"))

            val other = FakeEngine()
            probe(target = other).measure(sessions = 1, url = URI("https://staging.kadrohr.test/login"))
            other.sessions.single().actions shouldContainExactly listOf("navigate https://staging.kadrohr.test/login")
            other.events shouldContainExactly
                listOf("start contextsPerBrowser=20", "open capacity-1 base=https://staging.kadrohr.test/login", "stop")
        }

    @Test
    fun `all measured sessions share one browser, and everything is closed and stopped afterwards`() =
        runTest {
            probe(BrowserEngineConfig(contextsPerBrowser = 2)).measure(sessions = 4, url = null)

            engine.events.first() shouldBe "start contextsPerBrowser=4"
            engine.events.last() shouldBe "stop"
            engine.sessions.map { it.closed } shouldBe List(4) { true }
            engine.started shouldBe null
        }

    @Test
    fun `the topology of the run is kept for the measurement`() =
        runTest {
            probe(BrowserEngineConfig(topology = BrowserTopology.PER_SESSION, headless = false)).measure(sessions = 1, url = null)

            engine.configs.single() shouldBe BrowserEngineConfig(topology = BrowserTopology.PER_SESSION, headless = false)
        }

    @Test
    fun `readings wait for the processes to settle and keep the largest sample`() =
        runTest {
            var reads = 0
            val growing =
                ProcessMemory {
                    reads++
                    // The second of three samples after the sessions opened is the peak.
                    val base = 1024 * MIB + (if (engine.started != null) 300 * MIB else 0) + engine.openSessions() * 150 * MIB
                    if (reads == 6) base + 30 * MIB else base
                }

            val cost = probe(processMemory = growing).measure(sessions = 3, url = null)

            reads shouldBe 7
            currentTime shouldBe 2 * (2_000 + 2 * 500)
            cost.bytesPerSession shouldBe 150 * MIB + 10 * MIB + JVM_SHARE_PER_SESSION
        }

    @Test
    fun `a session that cannot open fails the measurement but still closes the others and stops the engine`() =
        runTest {
            val failing = FakeEngine(failOpening = 3)

            shouldThrow<BrowserActionException> { probe(target = failing).measure(sessions = 5, url = null) }

            failing.sessions.size shouldBe 2
            failing.sessions.all { it.closed } shouldBe true
            failing.events.last() shouldBe "stop"
        }

    @Test
    fun `memory that does not grow gives the minimum cost instead of zero`() =
        runTest {
            val flat = ProcessMemory { 1024 * MIB }

            val cost = probe(processMemory = flat).measure(sessions = 2, url = null)

            cost shouldBe SessionCost(MIN_SESSION_BYTES + JVM_SHARE_PER_SESSION, bytesPerBrowser = 0, measured = true)
        }

    @Test
    fun `at least one session must be measured`() =
        runTest {
            shouldThrow<IllegalArgumentException> { probe().measure(sessions = 0, url = null) }
            engine.events shouldBe emptyList()
        }
}
