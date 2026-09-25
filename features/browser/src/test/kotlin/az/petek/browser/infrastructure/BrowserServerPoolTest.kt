package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Sharding rules of the shared browser servers, with tiny shell programs standing in for the browser hosts. */
class BrowserServerPoolTest {
    private val launched = CopyOnWriteArrayList<BrowserServerProcess>()
    private val launches = AtomicInteger()

    /** A host that reports an endpoint and exits as soon as its stdin closes, like the real one. */
    private suspend fun fakeHost(): LaunchedServer {
        val number = launches.incrementAndGet()
        val command = ProcessBuilder("sh", "-c", "echo 'PETEK_WS_ENDPOINT=ws://127.0.0.1:1/server-$number'; exec cat > /dev/null")
        val process = BrowserServerProcess.start(command, 5.seconds).also { launched += it }
        return LaunchedServer(process, BrowserConnector.SharedServer(process.wsEndpoint, kotlin.time.Duration.ZERO), Thread {})
    }

    private fun pool(contextsPerBrowser: Int) = BrowserServerPool(contextsPerBrowser) { fakeHost() }

    @AfterEach
    fun killHosts() {
        launched.forEach { it.stop() }
    }

    @Test
    fun `the first server starts eagerly and hosts sessions up to the limit before a second one starts`() =
        runBlocking<Unit> {
            val pool = pool(contextsPerBrowser = 2)
            pool.startFirst()
            pool.processes() shouldHaveSize 1

            val leases = (1..5).map { pool.reserve() }

            pool.processes() shouldHaveSize 3
            pool.sessionsPerServer() shouldContainExactly listOf(2, 2, 1)
            leases.map { it.connector.toString() }.distinct() shouldHaveSize 1 // the connector hides the endpoint
            launches.get() shouldBe 3
        }

    @Test
    fun `released slots are reused before any new server starts, least-loaded server first`() =
        runBlocking<Unit> {
            val pool = pool(contextsPerBrowser = 3)
            pool.startFirst()
            val leases = (1..6).map { pool.reserve() }
            pool.sessionsPerServer() shouldContainExactly listOf(3, 3)

            leases[0].release()
            leases[1].release()
            leases[3].release()
            pool.sessionsPerServer() shouldContainExactly listOf(1, 2)

            pool.reserve()
            pool.sessionsPerServer() shouldContainExactly listOf(2, 2)
            pool.reserve()
            pool.reserve()
            pool.sessionsPerServer() shouldContainExactly listOf(3, 3)
            pool.processes() shouldHaveSize 2
        }

    @Test
    fun `releasing a lease twice frees only one slot`() =
        runBlocking<Unit> {
            val pool = pool(contextsPerBrowser = 2)
            pool.startFirst()
            val lease = pool.reserve()
            pool.reserve()

            lease.release()
            lease.release()

            pool.sessionsPerServer() shouldContainExactly listOf(1)
        }

    @Test
    fun `a server that died is skipped for new sessions`() =
        runBlocking<Unit> {
            val pool = pool(contextsPerBrowser = 5)
            pool.startFirst()
            pool.reserve()
            val dead = pool.processes().single()
            dead.stop()

            pool.reserve()

            pool.processes() shouldHaveSize 2
            pool.sessionsPerServer() shouldContainExactly listOf(1, 1)
        }

    @Test
    fun `concurrent reservations never start more servers than they need`() =
        runBlocking<Unit> {
            val pool = pool(contextsPerBrowser = 4)
            pool.startFirst()

            withContext(Dispatchers.Default) { (1..10).map { async { pool.reserve() } }.awaitAll() }

            pool.processes() shouldHaveSize 3
            pool.sessionsPerServer() shouldContainExactly listOf(4, 4, 2)
        }

    @Test
    fun `stopAll stops every server and later reservations fail`() =
        runBlocking<Unit> {
            val pool = pool(contextsPerBrowser = 1)
            pool.startFirst()
            repeat(3) { pool.reserve() }
            val trees = pool.processTrees().flatten()

            pool.stopAll()
            pool.stopAll()

            trees.forEach { process ->
                runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
                process.isAlive shouldBe false
            }
            shouldThrow<BrowserActionException> { pool.reserve() }.message shouldBe "the browser servers are stopped"
            launches.get() shouldBe 3
        }

    @Test
    fun `a server that finishes starting after stopAll is stopped at once`() =
        runBlocking<Unit> {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val pool =
                BrowserServerPool(contextsPerBrowser = 1) {
                    entered.complete(Unit)
                    gate.await()
                    fakeHost()
                }
            val starting = async(Dispatchers.Default) { runCatching { pool.startFirst() } }
            entered.await()

            pool.stopAll()
            gate.complete(Unit)

            starting.await().exceptionOrNull()?.message shouldBe "the browser engine was stopped while a browser server was starting"
            launched.single().isAlive shouldBe false
        }

    @Test
    fun `the limit must be at least one context per browser`() {
        shouldThrow<IllegalArgumentException> { pool(contextsPerBrowser = 0) }
    }
}
