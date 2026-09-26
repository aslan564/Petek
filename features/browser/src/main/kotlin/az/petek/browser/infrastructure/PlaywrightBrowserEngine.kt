/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * **SHARED_SERVER** runs Chromium as Playwright *browser servers* in child processes, and every session connects to
 * one with `BrowserType.connect(wsEndpoint)`. A server hosts at most [BrowserEngineConfig.contextsPerBrowser]
 * sessions; the first starts with the engine, further ones start lazily when every running server is full, and a
 * new session goes to the least-loaded server with room ([BrowserServerPool]). So any number of agents can run,
 * spread over as many browsers as they need. The host is Playwright's own Node.js driver from the jar running
 * [BundledScripts.browserServer]: the public `launchServer` API behind Playwright's hidden `launch-server` command,
 * plus a watchdog that closes the browser when the JVM disappears. Why this and not the alternative of launching
 * Chromium with `--remote-debugging-port` and `connectOverCDP`:
 * - `connect` speaks the full Playwright protocol, exactly as a locally launched browser does; Playwright documents
 *   CDP connections as significantly lower fidelity.
 * - The server owns the browser independently of any session: no Playwright instance (and thus no thread) has to
 *   stay alive just to keep Chromium running, and one crashed session cannot take the browser down.
 * - The server removes the contexts of a connection when it closes, so a lost agent does not leak contexts.
 * - Client and server always have the same version, since both come from the same jar.
 *
 * The costs: one extra Node.js process per server, a dependency on Playwright's `impl.driver` package to locate that
 * Node.js, and a WebSocket hop per call. Servers bind to 127.0.0.1 only and their URLs carry a random path. Every
 * browser server's process tree is killed by [stop], by a JVM shutdown hook, and (through the host's stdin watchdog)
 * even when the JVM is killed outright.
 *
 * **PER_SESSION** lets every session launch its own Chromium on its own thread: heavier, but independent of the
 * server protocol. Those browsers end with their session, or with their Playwright driver when the JVM exits.
 *
 * Opening a session starts a Playwright driver process, so at most [EngineSettings.maxConcurrentOpens] sessions open
 * at the same moment; hundreds of agents starting together then queue briefly instead of overloading the machine
 * and timing out. Chromium is installed on [start] when missing (a download on first use only). [start] fails with a
 * [BrowserActionException] explaining the reason when no browser can be started. [stop] closes all open sessions,
 * then every server, and is idempotent. With shared servers, a session still busy after a grace period (e.g. in a
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
                    BrowserTopology.SHARED_SERVER -> startSharedServers(config)
                    BrowserTopology.PER_SESSION -> startPerSession(config)
                }
            running = engine
            logger.info {
                "browser engine started (${config.topology}, headless=${config.headless}, " +
                    "contextsPerBrowser=${config.contextsPerBrowser})"
            }
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

    /** Every shared browser server's current process tree, flattened; empty for PER_SESSION or when stopped. For tests. */
    internal fun browserServerProcesses(): List<ProcessHandle> =
        running
            ?.servers
            ?.processTrees()
            .orEmpty()
            .flatten()

    /** The first shared browser server, when running. For tests. */
    internal fun browserServer(): BrowserServerProcess? = running?.servers?.processes()?.firstOrNull()

    /** All shared browser servers in start order. For tests. */
    internal fun browserServers(): List<BrowserServerProcess> = running?.servers?.processes().orEmpty()

    /** Open sessions per shared browser server, in start order. For tests. */
    internal fun sessionsPerServer(): List<Int> = running?.servers?.sessionsPerServer().orEmpty()

    /** The JVM shutdown hook registered for the first shared browser server, when running. For tests. */
    internal fun serverShutdownHook(): Thread? = running?.servers?.firstShutdownHook()

    private suspend fun startSharedServers(config: BrowserEngineConfig): RunningEngine {
        val pool = BrowserServerPool(config.contextsPerBrowser) { launchServer(config) }
        pool.startFirst()
        return RunningEngine(clock, settings, servers = pool)
    }

    private fun startPerSession(config: BrowserEngineConfig): RunningEngine =
        RunningEngine(clock, settings, ownBrowser = BrowserConnector.OwnBrowser(config.headless, config.slowMo, config.ignoreTlsErrors))

    /** Starts one browser server and registers the JVM shutdown hook that kills it. */
    private suspend fun launchServer(config: BrowserEngineConfig): LaunchedServer {
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
        return LaunchedServer(
            server,
            BrowserConnector.SharedServer(server.wsEndpoint, config.slowMo, ignoreTlsErrors = config.ignoreTlsErrors),
            hook,
        )
    }

    /**
     * One started run of the engine: where sessions get their browser (a slot in [pool] for SHARED_SERVER, their own
     * browser through [ownBrowser] for PER_SESSION) and the sessions still open.
     */
    private class RunningEngine(
        private val clock: HarnessClock,
        private val settings: EngineSettings,
        val servers: BrowserServerPool? = null,
        private val ownBrowser: BrowserConnector? = null,
    ) {
        private val lock = Any()
        private val sessions = LinkedHashSet<PlaywrightBrowserSession>()
        private val opening = Semaphore(settings.maxConcurrentOpens)
        private var accepting = true

        suspend fun open(options: SessionOptions): BrowserSession {
            requireAccepting(options.label)
            return opening.withPermit {
                requireAccepting(options.label)
                val lease = servers?.reserve()
                val connector = lease?.connector ?: requireNotNull(ownBrowser)
                val session =
                    try {
                        PlaywrightBrowserSession.open(options, connector, clock) { closed ->
                            forget(closed)
                            lease?.release()
                        }
                    } catch (e: Throwable) {
                        lease?.release()
                        throw e
                    }
                register(session, options.label)
            }
        }

        suspend fun shutdown() {
            val open =
                synchronized(lock) {
                    accepting = false
                    sessions.toList()
                }
            coroutineScope {
                val closing = launch { supervisorScope { open.forEach { session -> launch { session.close() } } } }
                if (withTimeoutOrNull(settings.sessionCloseGrace) { closing.join() } == null) {
                    logger.warn {
                        if (servers != null) {
                            "browser sessions still busy after ${settings.sessionCloseGrace}; stopping the browsers under them"
                        } else {
                            "browser sessions still busy after ${settings.sessionCloseGrace}; waiting for their current calls to time out"
                        }
                    }
                }
                servers?.stopAll()
                closing.join()
            }
        }

        private suspend fun register(
            session: PlaywrightBrowserSession,
            label: String,
        ): BrowserSession {
            val registered =
                synchronized(lock) {
                    if (accepting) sessions += session
                    accepting
                }
            if (!registered) {
                session.close()
                throw BrowserActionException("the browser engine was stopped while session '$label' was opening")
            }
            return session
        }

        private fun requireAccepting(label: String) {
            if (!synchronized(lock) { accepting }) {
                throw BrowserActionException("the browser engine is stopped; cannot open session '$label'")
            }
        }

        private fun forget(session: PlaywrightBrowserSession) {
            synchronized(lock) { sessions -= session }
        }
    }
}

/** Engine tuning that production leaves at its defaults; tests shorten it or point it at a broken browser. */
internal data class EngineSettings(
    val serverStartTimeout: Duration = 60.seconds,
    /** How long [PlaywrightBrowserEngine.stop] lets busy sessions finish before stopping the browsers under them. */
    val sessionCloseGrace: Duration = 10.seconds,
    /** Chromium binary for the shared servers; null uses the one Playwright installed. */
    val chromiumExecutable: Path? = null,
    /** Sessions that may be opening at the same moment (each starts a Playwright driver process). */
    val maxConcurrentOpens: Int = defaultConcurrentOpens(),
) {
    init {
        require(maxConcurrentOpens >= 1) { "maxConcurrentOpens must be at least 1, was $maxConcurrentOpens" }
    }

    private companion object {
        /** One opening session per core keeps start-up fast without starving the sessions already running. */
        fun defaultConcurrentOpens(): Int = Runtime.getRuntime().availableProcessors().coerceIn(MIN_CONCURRENT_OPENS, MAX_CONCURRENT_OPENS)

        const val MIN_CONCURRENT_OPENS = 4
        const val MAX_CONCURRENT_OPENS = 16
    }
}
