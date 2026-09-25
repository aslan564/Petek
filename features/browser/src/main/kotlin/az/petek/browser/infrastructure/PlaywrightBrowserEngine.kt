package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.BrowserTopology
import az.petek.browser.domain.SessionOptions
import az.petek.core.time.HarnessClock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * [BrowserEngine] on Playwright Java. Construct one per process in the composition root:
 * `PlaywrightBrowserEngine(clock)`, then `start(config)` once per run and `stop()` in a `finally`.
 *
 * Every session is a [PlaywrightBrowserSession]: its own thread, its own `Playwright` instance and its own isolated
 * browser context (CLAUDE.md rule 9). Where the browser comes from depends on [BrowserEngineConfig.topology]:
 *
 * **SHARED_SERVER** starts one Chromium as a Playwright *browser server* in a child process, and every session
 * connects to it with `BrowserType.connect(wsEndpoint)`. The host is Playwright's own Node.js driver from the jar
 * running [BundledScripts.browserServer]: the public `launchServer` API behind Playwright's hidden `launch-server`
 * command, plus a watchdog that closes the browser when the JVM disappears. Why this and not the alternative of
 * launching Chromium with `--remote-debugging-port` and `connectOverCDP`:
 * - `connect` speaks the full Playwright protocol, exactly as a locally launched browser does; Playwright documents
 *   CDP connections as significantly lower fidelity.
 * - The server owns the browser independently of any session: no Playwright instance (and thus no thread) has to
 *   stay alive just to keep Chromium running, and one crashed session cannot take the browser down.
 * - The server removes the contexts of a connection when it closes, so a lost agent does not leak contexts.
 * - Client and server always have the same version, since both come from the same jar.
 *
 * The costs: one extra Node.js process, a dependency on Playwright's `impl.driver` package to locate that Node.js,
 * and a WebSocket hop per call. The server binds to 127.0.0.1 only and its URL carries a random path. The browser
 * process tree is killed by [stop], by a JVM shutdown hook, and (through the host's stdin watchdog) even when the JVM
 * is killed outright.
 *
 * **PER_SESSION** lets every session launch its own Chromium on its own thread: heavier, but independent of the
 * server protocol. Those browsers end with their session, or with their Playwright driver when the JVM exits.
 *
 * Chromium is installed on [start] when missing (a download on first use only). [start] fails with a
 * [BrowserActionException] explaining the reason when no browser can be started. [stop] closes all open sessions,
 * then the server, and is idempotent. With a shared server, a session still busy after a grace period (e.g. in a
 * long wait) does not hold [stop] up: stopping the browser under it makes its pending call fail. After [stop] the
 * engine can be started again.
 */
class PlaywrightBrowserEngine internal constructor(
    private val clock: HarnessClock,
    private val driver: PlaywrightDriver,
    private val settings: EngineSettings,
) : BrowserEngine {
    constructor(clock: HarnessClock) : this(clock, PlaywrightDriver(), EngineSettings())

    private val lifecycle = Mutex()

    @Volatile
    private var running: RunningEngine? = null

    override suspend fun start(config: BrowserEngineConfig): BrowserSessionFactory =
        lifecycle.withLock {
            if (running != null) throw BrowserActionException("the browser engine is already started; stop it first")
            withContext(Dispatchers.IO) { driver.installChromium() }
            val engine =
                when (config.topology) {
                    BrowserTopology.SHARED_SERVER -> startSharedServer(config)
                    BrowserTopology.PER_SESSION -> startPerSession(config)
                }
            running = engine
            logger.info { "browser engine started (${config.topology}, headless=${config.headless})" }
            BrowserSessionFactory { options -> engine.open(options) }
        }

    override suspend fun stop() {
        withContext(NonCancellable) {
            lifecycle.withLock {
                val engine = running ?: return@withLock
                running = null
                engine.shutdown()
                logger.info { "browser engine stopped" }
            }
        }
    }

    /** The shared browser server's current process tree; empty for PER_SESSION or when stopped. For tests. */
    internal fun browserServerProcesses(): List<ProcessHandle> = running?.server?.processTree().orEmpty()

    /** The shared browser server itself, when running. For tests. */
    internal fun browserServer(): BrowserServerProcess? = running?.server

    /** The JVM shutdown hook registered for the shared browser server, when running. For tests. */
    internal fun serverShutdownHook(): Thread? = running?.shutdownHook

    private suspend fun startSharedServer(config: BrowserEngineConfig): RunningEngine {
        val options = BrowserServerOptions(headless = config.headless, executablePath = settings.chromiumExecutable)
        val command = withContext(Dispatchers.IO) { driver.browserServerCommand(options.toJson()) }
        val server =
            try {
                BrowserServerProcess.start(command, settings.serverStartTimeout)
            } catch (e: BrowserActionException) {
                throw BrowserActionException("could not start the shared Chromium browser server: ${e.message}", e)
            }
        val hook = Thread({ server.stop() }, "petek-browser-server-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        val connector = BrowserConnector.SharedServer(server.wsEndpoint, config.slowMo)
        return RunningEngine(connector, clock, settings.sessionCloseGrace, server, hook)
    }

    private fun startPerSession(config: BrowserEngineConfig): RunningEngine =
        RunningEngine(BrowserConnector.OwnBrowser(config.headless, config.slowMo), clock, settings.sessionCloseGrace)

    /** One started run of the engine: the way sessions get a browser, the sessions still open, and the server. */
    private class RunningEngine(
        private val connector: BrowserConnector,
        private val clock: HarnessClock,
        private val sessionCloseGrace: Duration,
        val server: BrowserServerProcess? = null,
        val shutdownHook: Thread? = null,
    ) {
        private val lock = Any()
        private val sessions = LinkedHashSet<PlaywrightBrowserSession>()
        private var accepting = true

        suspend fun open(options: SessionOptions): BrowserSession {
            if (!isAccepting()) throw BrowserActionException("the browser engine is stopped; cannot open session '${options.label}'")
            val session = PlaywrightBrowserSession.open(options, connector, clock, onClosed = ::forget)
            val registered =
                synchronized(lock) {
                    if (accepting) sessions += session
                    accepting
                }
            if (!registered) {
                session.close()
                throw BrowserActionException("the browser engine was stopped while session '${options.label}' was opening")
            }
            return session
        }

        suspend fun shutdown() {
            val open =
                synchronized(lock) {
                    accepting = false
                    sessions.toList()
                }
            coroutineScope {
                val closing = launch { supervisorScope { open.forEach { session -> launch { session.close() } } } }
                if (withTimeoutOrNull(sessionCloseGrace) { closing.join() } == null) {
                    logger.warn { "browser sessions still busy after $sessionCloseGrace; stopping the browser under them" }
                }
                if (server != null) withContext(Dispatchers.IO) { server.stop() }
                closing.join()
            }
            shutdownHook?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
        }

        private fun isAccepting(): Boolean = synchronized(lock) { accepting }

        private fun forget(session: PlaywrightBrowserSession) {
            synchronized(lock) { sessions -= session }
        }
    }
}

/** Engine tuning that production leaves at its defaults; tests shorten it or point it at a broken browser. */
internal data class EngineSettings(
    val serverStartTimeout: Duration = 60.seconds,
    /** How long [PlaywrightBrowserEngine.stop] lets busy sessions finish before stopping the browser under them. */
    val sessionCloseGrace: Duration = 10.seconds,
    /** Chromium binary for the shared server; null uses the one Playwright installed. */
    val chromiumExecutable: Path? = null,
)
