package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.BrowserTopology
import az.petek.browser.domain.SessionOptions
import az.petek.core.time.SystemHarnessClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class PlaywrightBrowserEngineTest {
    private val clock = SystemHarnessClock()
    private val site = TestSite()
    private val engine = PlaywrightBrowserEngine(clock)

    @AfterEach
    fun cleanUp() {
        runBlocking { engine.stop() }
        site.close()
    }

    @Test
    fun `ten sessions used concurrently from Dispatchers Default share one browser server and stay isolated`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig(topology = BrowserTopology.SHARED_SERVER))

            val results =
                withContext(Dispatchers.Default) {
                    val sessions = (1..10).map { i -> async { open(factory, "agent-$i") } }.awaitAll()
                    try {
                        val serverPid = engine.browserServerProcesses().first().pid()
                        val chromes = chromeProcessesOfThisJvm()
                        chromes.shouldNotBeEmpty()
                        chromes.filter { it.isAlive && !it.hasAncestor(serverPid) }.shouldBeEmpty()
                        sessions
                            .mapIndexed { index, session -> async { loginAndReport(session, "user${index + 1}") } }
                            .awaitAll()
                    } finally {
                        sessions.map { async { it.close() } }.awaitAll()
                    }
                }

            results shouldContainExactlyInAnyOrder
                (1..10).map { SessionReport("browser-agent-$it", "Salam, user$it", "user$it", "local=user$it") }
        }

    @Test
    fun `sessions beyond contextsPerBrowser start further browser servers and every session still works`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig(contextsPerBrowser = 2))
            engine.browserServers() shouldHaveSize 1

            val sessions = (1..5).map { open(factory, "shard-$it") }
            try {
                engine.browserServers() shouldHaveSize 3
                engine.sessionsPerServer() shouldContainExactly listOf(2, 2, 1)
                val servers = engine.browserServers().map { it.processTree().first().pid() }
                chromeProcessesOfThisJvm().filter { chrome -> servers.none { chrome.hasAncestor(it) } }.shouldBeEmpty()
                sessions.forEachIndexed { index, session ->
                    loginAndReport(session, "shard${index + 1}").greeting shouldBe "Salam, shard${index + 1}"
                }
            } finally {
                sessions.forEach { it.close() }
            }
            engine.sessionsPerServer() shouldContainExactly listOf(0, 0, 0)
        }

    @Test
    fun `closing sessions frees their slots and new sessions fill the least-loaded server first`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig(contextsPerBrowser = 2))
            val sessions = (1..5).map { open(factory, "slot-$it") }.toMutableList()
            try {
                sessions.removeAt(0).close()
                sessions.removeAt(0).close()
                engine.sessionsPerServer() shouldContainExactly listOf(0, 2, 1)

                sessions += open(factory, "slot-6")
                engine.sessionsPerServer() shouldContainExactly listOf(1, 2, 1)
                sessions += open(factory, "slot-7")
                sessions += open(factory, "slot-8")
                engine.sessionsPerServer() shouldContainExactly listOf(2, 2, 2)

                engine.browserServers() shouldHaveSize 3
                sessions.last().navigate("/form")
                sessions.last().isTextVisible("Qeydiyyat") shouldBe true
            } finally {
                sessions.forEach { it.close() }
            }
        }

    @Test
    fun `stop kills the process trees of every browser server`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig(contextsPerBrowser = 1))
            val sessions = (1..3).map { open(factory, "many-$it") }
            sessions.forEach { it.navigate("/form") }
            engine.browserServers() shouldHaveSize 3
            val trees = engine.browserServers().map { it.processTree() }
            trees.forEach { it.size shouldBeGreaterThanOrEqual 2 }

            engine.stop()

            trees.flatten().forEach { it.awaitExit() }
            engine.browserServers().shouldBeEmpty()
            sessions.forEach { session ->
                shouldThrow<BrowserActionException> { session.snapshot() }.message shouldContain "is closed"
            }
        }

    @Test
    fun `sessions opening all at once never start more browser servers than they need`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig(contextsPerBrowser = 3))

            val sessions = withContext(Dispatchers.Default) { (1..7).map { async { open(factory, "burst-$it") } }.awaitAll() }
            try {
                engine.browserServers() shouldHaveSize 3
                engine.sessionsPerServer().sorted() shouldContainExactly listOf(1, 3, 3)
            } finally {
                sessions.forEach { it.close() }
            }
        }

    @Test
    fun `per-session topology gives every session its own browser`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig(topology = BrowserTopology.PER_SESSION))
            engine.browserServerProcesses().shouldBeEmpty()

            val first = open(factory, "own-1")
            val second = open(factory, "own-2")
            val chromes = chromeProcessesOfThisJvm()
            try {
                chromes.shouldNotBeEmpty()
                loginAndReport(first, "nərmin") shouldBe SessionReport("browser-own-1", "Salam, nərmin", "nərmin", "local=nərmin")
                loginAndReport(second, "tural") shouldBe SessionReport("browser-own-2", "Salam, tural", "tural", "local=tural")
            } finally {
                first.close()
                second.close()
            }
            chromes.forEach { it.awaitExit() }
        }

    @Test
    fun `stop closes open sessions and kills the whole browser server process tree`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig())
            val session = open(factory, "left-open")
            session.navigate("/form")
            val tree = engine.browserServerProcesses()
            tree.size shouldBeGreaterThanOrEqual 2
            val chromes = chromeProcessesOfThisJvm()

            engine.stop()

            (tree + chromes).forEach { it.awaitExit() }
            shouldThrow<BrowserActionException> { session.snapshot() }.message shouldBe "browser session 'left-open' is closed"
            shouldThrow<BrowserActionException> { factory.open(SessionOptions("late", site.baseUrl)) }
                .message shouldContain "stopped"
            engine.stop()
        }

    @Test
    fun `slow motion from the engine config slows every session down`() =
        runBlocking<Unit> {
            val factory = engine.start(BrowserEngineConfig(slowMo = 150.milliseconds))
            val session = open(factory, "slow")
            try {
                session.navigate("/dynamic")

                val took = measureTime { session.clickSelector("#add") }

                took shouldBeGreaterThanOrEqualTo 150.milliseconds
                session.count("button") shouldBe 2
            } finally {
                session.close()
            }
        }

    @Test
    fun `the engine can be started again after stop but not twice at once`() =
        runBlocking<Unit> {
            engine.start(BrowserEngineConfig())
            shouldThrow<BrowserActionException> { engine.start(BrowserEngineConfig()) }.message shouldContain "already started"
            engine.stop()

            val factory = engine.start(BrowserEngineConfig())
            val session = open(factory, "again")
            try {
                session.navigate("/form")
                session.isTextVisible("Qeydiyyat") shouldBe true
            } finally {
                session.close()
            }
        }

    @Test
    fun `a browser server that cannot launch Chromium fails with a clear reason`() =
        runBlocking<Unit> {
            val settings = EngineSettings(chromiumExecutable = Path.of("/nonexistent/petek-chrome"))
            val broken = PlaywrightBrowserEngine(clock, PlaywrightDriver(), settings)

            val failure = shouldThrow<BrowserActionException> { broken.start(BrowserEngineConfig()) }

            failure.message shouldContain "could not start the shared Chromium browser server"
            failure.message shouldContain "exited with code 1 before it was ready"
            failure.message shouldContain "executable doesn't exist at /nonexistent/petek-chrome"
            failure.message shouldNotContain "coreBundle.js"
            broken.browserServerProcesses().shouldBeEmpty()
            broken.stop()
        }

    @Test
    fun `the JVM shutdown hook kills the browser server`() =
        runBlocking<Unit> {
            engine.start(BrowserEngineConfig())
            val hook = engine.serverShutdownHook().shouldNotBeNull()
            val tree = engine.browserServerProcesses()

            Runtime.getRuntime().removeShutdownHook(hook) shouldBe true
            hook.run()

            tree.forEach { it.awaitExit() }
        }

    @Test
    fun `stop does not wait for a session stuck in a long wait`() =
        runBlocking<Unit> {
            val patient = PlaywrightBrowserEngine(clock, PlaywrightDriver(), EngineSettings(sessionCloseGrace = 300.milliseconds))
            val factory = patient.start(BrowserEngineConfig())
            val session = open(factory, "stuck")
            session.navigate("/form")
            // UNDISPATCHED: the wait is handed to the session thread before stop() queues the session's close behind it.
            val waiting =
                async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.waitForText("never shown", 60.seconds) } }

            val took = measureTime { patient.stop() }

            took shouldBeLessThan 15.seconds
            waiting.await().exceptionOrNull().shouldBeInstanceOf<BrowserActionException>()
        }

    @Test
    fun `the browser server closes Chromium when its parent goes away`() =
        runBlocking<Unit> {
            engine.start(BrowserEngineConfig())
            val server = engine.browserServer().shouldNotBeNull()
            val tree = server.processTree()

            server.closeInput()

            tree.forEach { it.awaitExit() }
        }

    private data class SessionReport(
        val thread: String,
        val greeting: String?,
        val apiUser: String,
        val localStorage: String?,
    )

    private suspend fun open(
        factory: BrowserSessionFactory,
        label: String,
    ): PlaywrightBrowserSession = factory.open(SessionOptions(label, site.baseUrl)) as PlaywrightBrowserSession

    /** Logs in through the page using snapshot refs, then reads back what the site thinks of this session. */
    private suspend fun loginAndReport(
        session: BrowserSession,
        user: String,
    ): SessionReport {
        session.navigate("/login")
        val snapshot = session.snapshot()
        val input = snapshot.elements.single { it.testId == "user" }.ref
        val button = snapshot.elements.single { it.testId == "login" }.ref
        session.fill(input, user)
        session.click(button)
        session.waitForText("Salam, $user", 10.seconds).found shouldBe true
        return SessionReport(
            thread = (session as PlaywrightBrowserSession).threadName,
            greeting = session.readText("#greeting"),
            apiUser = session.request("GET", "/api/me").body,
            localStorage = session.readText("#local"),
        )
    }

    /** Chromium processes started, directly or not, by this JVM. */
    private fun chromeProcessesOfThisJvm(): List<ProcessHandle> =
        ProcessHandle
            .current()
            .descendants()
            .filter { process ->
                process.isAlive &&
                    process
                        .info()
                        .command()
                        .orElse("")
                        .contains("chrom")
            }.toList()

    private fun ProcessHandle.hasAncestor(pid: Long): Boolean =
        generateSequence(parent().orElse(null)) { it.parent().orElse(null) }.any { it.pid() == pid }

    /** Waits (bounded) for the process to end and asserts that it did. */
    private fun ProcessHandle.awaitExit() {
        runCatching { onExit().get(10, TimeUnit.SECONDS) }
        isAlive shouldBe false
    }
}
