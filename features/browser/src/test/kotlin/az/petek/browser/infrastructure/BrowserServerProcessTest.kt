package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** Process lifecycle rules, exercised with small shell programs standing in for the Node.js host. */
class BrowserServerProcessTest {
    private fun shell(script: String) = ProcessBuilder("sh", "-c", script)

    @Test
    fun `the endpoint is read from the host output among other lines`() =
        runBlocking<Unit> {
            val server =
                BrowserServerProcess.start(
                    shell("echo starting; echo 'PETEK_WS_ENDPOINT=ws://127.0.0.1:4567/abc' ; exec sleep 60"),
                    startupTimeout = 5.seconds,
                )
            try {
                server.wsEndpoint shouldBe "ws://127.0.0.1:4567/abc"
                server.isAlive shouldBe true
            } finally {
                server.stop(gracePeriod = 100.milliseconds)
            }
            server.isAlive shouldBe false
        }

    @Test
    fun `a host that exits before it is ready fails with its exit code and output`() =
        runBlocking<Unit> {
            val failure =
                shouldThrow<BrowserActionException> {
                    BrowserServerProcess.start(shell("echo 'Executable does not exist' >&2; exit 3"), startupTimeout = 5.seconds)
                }

            failure.message shouldContain "exited with code 3 before it was ready"
            failure.message shouldContain "Executable does not exist"
        }

    @Test
    fun `a host that never reports an endpoint is killed after the startup timeout`() =
        runBlocking<Unit> {
            val marker = "silent-host-${System.nanoTime()}"

            val failure =
                shouldThrow<BrowserActionException> {
                    BrowserServerProcess.start(shell("echo $marker; exec sleep 62"), startupTimeout = 300.milliseconds)
                }

            failure.message shouldContain "did not become ready within 300ms"
            failure.message shouldContain marker
            descendantsRunning("sleep 62") shouldBe emptyList()
        }

    @Test
    fun `stop kills the whole tree even when the host ignores every polite request`() =
        runBlocking<Unit> {
            val server =
                BrowserServerProcess.start(
                    shell("trap '' TERM; sleep 61 & echo PETEK_WS_ENDPOINT=ws://x; wait"),
                    startupTimeout = 5.seconds,
                )
            val tree = server.processTree()
            tree shouldHaveSize 2

            server.stop(gracePeriod = 100.milliseconds)
            server.stop(gracePeriod = 100.milliseconds)

            tree.forEach { process ->
                runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
                process.isAlive shouldBe false
            }
        }

    @Test
    fun `a host that exits on stdin EOF is stopped gracefully`() =
        runBlocking<Unit> {
            val server =
                BrowserServerProcess.start(
                    shell("echo PETEK_WS_ENDPOINT=ws://graceful; cat > /dev/null; echo bye"),
                    startupTimeout = 5.seconds,
                )

            val took = measureTime { server.stop(gracePeriod = 5.seconds) }

            server.isAlive shouldBe false
            took shouldBeLessThan 4.seconds
        }

    @Test
    fun `a command that cannot be started is reported as such`() =
        runBlocking<Unit> {
            val failure =
                shouldThrow<BrowserActionException> {
                    BrowserServerProcess.start(ProcessBuilder("/nonexistent/petek-node"), startupTimeout = 1.seconds)
                }

            failure.message shouldContain "could not start the browser server process"
            failure.message shouldNotContain "PETEK_WS_ENDPOINT"
        }

    private fun descendantsRunning(command: String): List<String> =
        ProcessHandle
            .current()
            .descendants()
            .filter { it.isAlive }
            .map { it.info().commandLine().orElse("") }
            .filter { it.contains(command) }
            .toList()
}
