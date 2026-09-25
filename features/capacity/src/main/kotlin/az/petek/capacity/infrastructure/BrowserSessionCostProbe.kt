package az.petek.capacity.infrastructure

import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.SessionOptions
import az.petek.capacity.domain.Bytes
import az.petek.capacity.domain.SessionCost
import az.petek.capacity.domain.SessionCostProbe
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * [SessionCostProbe] that measures real browser sessions on this machine. Construct it in the composition root with
 * a browser engine that is not running and the [BrowserEngineConfig] a run would use:
 * `BrowserSessionCostProbe(PlaywrightBrowserEngine(clock), config)`.
 *
 * A measurement reads the memory of this JVM's descendant processes ([ProcProcessMemory]) three times: before the
 * engine starts, once the engine's first browser is idle (the difference is the cost per browser) and once `sessions`
 * sessions have opened a page and settled (the difference, divided by `sessions`, is the cost per session). It then
 * closes the sessions and stops the engine, also when anything fails. All measured sessions share one browser
 * (`contextsPerBrowser` is raised to `sessions` for the measurement); with one browser per session (PER_SESSION) the
 * browser shows up in the per-session cost instead. Each reading waits [Timing.settle] and keeps the largest of
 * [Timing.samples] samples, so a renderer still growing is not missed.
 *
 * The session's share of the JVM itself (its thread, Playwright connection, screenshots and prompts in flight) is not
 * a separate process; [JVM_SHARE_PER_SESSION], an estimate, is added for it. The caller checks the target policy
 * before measuring on a real site; loading a page is all the probe does there.
 */
class BrowserSessionCostProbe internal constructor(
    private val engine: BrowserEngine,
    private val config: BrowserEngineConfig,
    private val memory: ProcessMemory,
    private val timing: Timing,
) : SessionCostProbe {
    constructor(engine: BrowserEngine, config: BrowserEngineConfig) : this(engine, config, ProcProcessMemory(), Timing())

    /** How long a reading waits for the processes to settle, and how it samples. */
    internal data class Timing(
        val settle: Duration = 2.seconds,
        val samples: Int = 3,
        val sampleInterval: Duration = 500.milliseconds,
    ) {
        init {
            require(samples >= 1) { "at least one sample is needed, was $samples" }
        }
    }

    override suspend fun measure(
        sessions: Int,
        url: URI?,
    ): SessionCost {
        require(sessions >= 1) { "at least 1 session must be measured, was $sessions" }
        val baseline = read()
        val factory = engine.start(config.copy(contextsPerBrowser = maxOf(config.contextsPerBrowser, sessions)))
        try {
            val withBrowser = settledReading()
            val opened = ArrayList<BrowserSession>(sessions)
            try {
                repeat(sessions) { i ->
                    val session = factory.open(SessionOptions(label = "capacity-${i + 1}", baseUrl = url ?: UNUSED_BASE_URL))
                    opened += session
                    session.navigate(url?.toString() ?: BLANK_PAGE)
                }
                val withSessions = settledReading()
                return costOf(baseline, withBrowser, withSessions, sessions).also {
                    logger.info {
                        "measured $sessions sessions: ${Bytes.format(it.bytesPerSession)} per session, " +
                            "${Bytes.format(it.bytesPerBrowser)} per browser"
                    }
                }
            } finally {
                withContext(NonCancellable) { opened.forEach { runCatching { it.close() } } }
            }
        } finally {
            withContext(NonCancellable) { engine.stop() }
        }
    }

    private fun costOf(
        baseline: Long,
        withBrowser: Long,
        withSessions: Long,
        sessions: Int,
    ): SessionCost {
        val perBrowser = (withBrowser - baseline).coerceAtLeast(0)
        val perSession = ((withSessions - withBrowser) / sessions).coerceAtLeast(MIN_SESSION_BYTES) + JVM_SHARE_PER_SESSION
        return SessionCost(bytesPerSession = perSession, bytesPerBrowser = perBrowser, measured = true)
    }

    /** The largest of a few samples, taken once the processes had [Timing.settle] to finish starting and loading. */
    private suspend fun settledReading(): Long {
        delay(timing.settle)
        var largest = read()
        repeat(timing.samples - 1) {
            delay(timing.sampleInterval)
            largest = maxOf(largest, read())
        }
        return largest
    }

    private suspend fun read(): Long = withContext(Dispatchers.IO) { memory.descendantBytes() }

    internal companion object {
        /** The JVM's share of one session, which no process of its own shows: an estimate. */
        const val JVM_SHARE_PER_SESSION: Long = 8 * Bytes.MIB

        /** A per-session growth below this is measurement noise, not a real cost. */
        const val MIN_SESSION_BYTES: Long = Bytes.MIB

        private const val BLANK_PAGE = "about:blank"

        /** Sessions need a base URL; on a blank page nothing is ever resolved against it. */
        private val UNUSED_BASE_URL = URI("http://localhost/")
    }
}
