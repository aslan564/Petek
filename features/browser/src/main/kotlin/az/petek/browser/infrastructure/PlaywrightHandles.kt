package az.petek.browser.infrastructure

import az.petek.browser.domain.SessionOptions
import az.petek.core.time.HarnessClock
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Request
import com.microsoft.playwright.Response
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * This duration as a Playwright timeout in milliseconds: at least 1 ms, because Playwright reads 0 as "never time
 * out", and at most [Int.MAX_VALUE] ms, because the Node.js driver's timers fire at once for longer delays (so a
 * [Duration.INFINITE] wait would otherwise end immediately).
 */
internal fun Duration.toPlaywrightTimeout(): Double = inWholeMilliseconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toDouble()

/**
 * The Playwright objects owned by one session: its own [Playwright] instance (driver process), the browser it got
 * from a [BrowserConnector], one isolated context and one page. Created and released on the session thread only.
 */
internal class PlaywrightHandles private constructor(
    val playwright: Playwright,
    val browser: Browser,
    val context: BrowserContext,
    val page: Page,
) {
    /** Closes in dependency order and keeps going when a step fails, so the driver process always ends. */
    fun release() {
        listOf<Pair<String, () -> Unit>>(
            "context" to { context.close() },
            "browser" to { browser.close() },
            "playwright" to { playwright.close() },
        ).forEach { (what, close) ->
            runCatching(close).onFailure { logger.debug { "closing the $what failed: ${it.message}" } }
        }
    }

    companion object {
        /** Every context speaks the target's language and time zone, whatever the machine running Pətək uses. */
        const val LOCALE = "az-AZ"
        const val TIMEZONE = "Asia/Baku"

        private const val EVENT_STREAM = "text/event-stream"
        private val FETCH_TYPES = setOf("xhr", "fetch")

        /** Must run on the session thread. Releases whatever was created when a later step fails. */
        fun create(
            options: SessionOptions,
            connector: BrowserConnector,
            traffic: RealtimeTrafficRecorder,
            dialogs: DialogRecorder,
            clock: HarnessClock,
        ): PlaywrightHandles {
            val playwright = Playwright.create()
            try {
                val browser = connector.connect(playwright)
                val context = browser.newContext(contextOptions(options))
                context.setDefaultTimeout(options.defaultTimeout.toPlaywrightTimeout())
                LocalStorageSeed.script(options)?.let(context::addInitScript)
                val page = context.newPage()
                observeRealtimeTraffic(page, traffic, clock)
                acceptDialogs(page, dialogs, clock)
                return PlaywrightHandles(playwright, browser, context, page)
            } catch (e: Exception) {
                runCatching { playwright.close() }
                throw e
            }
        }

        /**
         * Accepts every dialog the page opens and records it. Without a handler Playwright dismisses dialogs, which
         * turns a `confirm("Delete?")` into a silent "no" the agent never hears about. Accepting follows what a
         * tester clicking OK would do; a prompt gets its default text. The handler runs on the session thread,
         * while one of the session's own calls waits, so answering here is the documented Playwright pattern.
         */
        private fun acceptDialogs(
            page: Page,
            dialogs: DialogRecorder,
            clock: HarnessClock,
        ) {
            page.onDialog { dialog ->
                dialogs.record(dialog.type(), dialog.message(), clock.now())
                runCatching { if (dialog.type() == "prompt") dialog.accept(dialog.defaultValue()) else dialog.accept() }
                    .onFailure { logger.debug { "accepting a ${dialog.type()} dialog failed: ${it.message}" } }
            }
        }

        private fun contextOptions(options: SessionOptions): Browser.NewContextOptions =
            Browser
                .NewContextOptions()
                .setBaseURL(options.baseUrl.toString())
                .setViewportSize(options.viewport.width, options.viewport.height)
                .setLocale(LOCALE)
                .setTimezoneId(TIMEZONE)
                .apply { options.storageState?.let { setStorageStatePath(it) } }

        /** Registered at page creation; Playwright invokes the handlers on the session thread as well. */
        private fun observeRealtimeTraffic(
            page: Page,
            traffic: RealtimeTrafficRecorder,
            clock: HarnessClock,
        ) {
            page.onWebSocket { socket -> traffic.webSocketOpened(socket.url()) }
            page.onRequest { request -> if (request.resourceType() == "eventsource") traffic.eventStreamSeen(request.url()) }
            page.onResponse { response -> recordResponse(response, traffic, clock) }
        }

        private fun recordResponse(
            response: Response,
            traffic: RealtimeTrafficRecorder,
            clock: HarnessClock,
        ) {
            val request = response.request()
            if (response.headers()["content-type"]?.startsWith(EVENT_STREAM) == true) traffic.eventStreamSeen(response.url())
            if (request.resourceType() in FETCH_TYPES) traffic.fetchCompleted(request.method(), request.url(), startedAt(request, clock))
        }

        /** The browser-side start time; the harness clock only when the browser did not report one. */
        private fun startedAt(
            request: Request,
            clock: HarnessClock,
        ): Double {
            val browserStart = request.timing().startTime
            if (browserStart > 0) return browserStart
            val harnessWall = clock.now().wall
            return harnessWall.toEpochMilli().toDouble()
        }
    }
}
