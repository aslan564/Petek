package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.SessionOptions
import az.petek.browser.domain.WaitOutcome
import az.petek.core.time.HarnessClock
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.SelectOption
import com.microsoft.playwright.options.WaitForSelectorState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * [BrowserSession] on Playwright: one agent's isolated browser context (own cookies, localStorage and
 * sessionStorage) with a single page, created by the session's own [Playwright] instance on its own thread named
 * `browser-<label>`. Every Playwright object is created and used only on that thread (CLAUDE.md rule 9); the
 * suspend functions may be called from any coroutine and run one at a time, in call order.
 *
 * Contract details the callers rely on:
 * - Element refs come from the latest [snapshot] (`data-petek-ref` attributes); a stale ref fails with
 *   "element N not found, take a new snapshot" instead of acting on the wrong element.
 * - Selector functions act on the first *visible* match, so a hidden duplicate (mobile menu, template) is skipped.
 * - [waitForText] and [waitForSelector] never throw on timeout: they return `found = false`. On success,
 *   [WaitOutcome.observedAt] is taken from the harness clock right after the page reported the text or element
 *   visible (rule 1). The check is polled *inside* the page every [PROBE_POLLING_INTERVAL_MS] ms rather than with
 *   `getByText(...).waitFor()`, whose retries back off to 500 ms and would blur a real-time latency (t1 − t0).
 *   Text matching follows `getByText`: case-insensitive, whitespace-normalized substring of the rendered text.
 *   Selectors that are plain CSS are probed the same way; Playwright-only syntax (`text=…`) uses a locator wait.
 * - [request] does not follow redirects, so an `http_status` assertion sees the endpoint's own status.
 * - Password values never leave the adapter: snapshots show `******`, DOM and ARIA snapshots are redacted, and
 *   text typed with [fill] is masked in error messages.
 * - Failures surface as [BrowserActionException] with a short reason. After [close], calls fail the same way.
 */
internal class PlaywrightBrowserSession private constructor(
    private val options: SessionOptions,
    private val thread: ConfinedThread,
    private val clock: HarnessClock,
    private val handles: PlaywrightHandles,
    private val traffic: RealtimeTrafficRecorder,
    private val onClosed: (PlaywrightBrowserSession) -> Unit,
) : BrowserSession {
    private val closed = AtomicBoolean(false)
    private val page: Page get() = handles.page

    override val label: String get() = options.label

    /** Name of the thread every Playwright call of this session runs on. */
    val threadName: String get() = thread.name

    override suspend fun navigate(pathOrUrl: String) {
        perform("navigate to $pathOrUrl") { page.navigate(pathOrUrl) }
    }

    override suspend fun snapshot(): PageSnapshot =
        perform("snapshot") {
            surviveNavigation {
                SnapshotParser.parse(page.evaluate(BundledScripts.pageIndexer, SnapshotLimits().asScriptArgument()))
            }
        }

    override suspend fun click(ref: Int) {
        perform("click [$ref]") { elementByRef(ref).click() }
    }

    override suspend fun fill(
        ref: Int,
        text: String,
        submit: Boolean,
    ) {
        perform("fill [$ref]", typedText = text) {
            val element = elementByRef(ref)
            element.fill(text)
            if (submit) element.press("Enter")
        }
    }

    override suspend fun select(
        ref: Int,
        option: String,
    ) {
        perform("select \"$option\" in [$ref]") { selectOption(elementByRef(ref), option) }
    }

    override suspend fun clickSelector(selector: String) {
        perform("click $selector") { firstVisible(selector).click() }
    }

    override suspend fun fillSelector(
        selector: String,
        text: String,
    ) {
        perform("fill $selector", typedText = text) { firstVisible(selector).fill(text) }
    }

    override suspend fun selectSelector(
        selector: String,
        option: String,
    ) {
        perform("select \"$option\" in $selector") { selectOption(firstVisible(selector), option) }
    }

    override suspend fun readText(selector: String): String? =
        perform("read text of $selector") {
            val matches = page.locator(selector)
            if (matches.count() == 0) null else matches.first().innerText().trim()
        }

    override suspend fun readAttribute(
        selector: String,
        attribute: String,
    ): String? =
        perform("read $attribute of $selector") {
            val matches = page.locator(selector)
            if (matches.count() == 0) null else matches.first().getAttribute(attribute)
        }

    override suspend fun waitForText(
        text: String,
        timeout: Duration,
    ): WaitOutcome = perform("wait for text \"$text\"") { awaitProbe(mapOf("text" to text), timeout) }

    override suspend fun waitForSelector(
        selector: String,
        timeout: Duration,
    ): WaitOutcome =
        perform("wait for $selector") {
            if (page.evaluate(BundledScripts.isCssSelector, selector) == true) {
                awaitProbe(mapOf("selector" to selector), timeout)
            } else {
                awaitLocator(page.locator(selector), timeout)
            }
        }

    override suspend fun isTextVisible(text: String): Boolean = perform("check text \"$text\"") { probe(mapOf("text" to text)) }

    override suspend fun isSelectorVisible(selector: String): Boolean =
        perform("check $selector") { page.locator(selector).filter(visibleOnly()).count() > 0 }

    override suspend fun count(selector: String): Int = perform("count $selector") { page.locator(selector).count() }

    override suspend fun currentUrl(): String = perform("read the URL") { page.url() }

    override suspend fun screenshot(): ByteArray = perform("screenshot") { page.screenshot() }

    override suspend fun accessibilitySnapshot(): String =
        perform("accessibility snapshot") {
            surviveNavigation { SecretRedactor.redactAriaSnapshot(page.locator("body").ariaSnapshot(), secretValues()) }
        }

    override suspend fun domSnapshot(): String =
        perform("DOM snapshot") {
            surviveNavigation { SecretRedactor.redactText(page.evaluate(BundledScripts.domSnapshot) as? String ?: "", secretValues()) }
        }

    override suspend fun saveStorageState(path: Path) {
        perform("save storage state") {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            handles.context.storageState(BrowserContext.StorageStateOptions().setPath(path))
        }
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult =
        perform("${method.uppercase()} $path") {
            val request = RequestOptions.create().setMethod(method.uppercase()).setMaxRedirects(0)
            if (body != null) {
                request.setData(body)
                if (body.trimStart().let { it.startsWith("{") || it.startsWith("[") }) {
                    request.setHeader("Content-Type", "application/json")
                }
            }
            val response = handles.context.request().fetch(path, request)
            try {
                HttpProbeResult(response.status(), response.text())
            } finally {
                response.dispose()
            }
        }

    override suspend fun networkObservation(): NetworkObservation =
        perform("observe network traffic") {
            // A round trip to the browser makes Playwright dispatch the traffic events it has already received.
            runCatching { page.title() }
            traffic.observation()
        }

    /** Idempotent. Releases the context, the browser connection (or own browser) and the Playwright driver. */
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            thread.runToCompletion { handles.release() }
        } finally {
            thread.close()
            onClosed(this)
            logger.debug { "browser session '$label' closed" }
        }
    }

    private suspend fun <T> perform(
        action: String,
        typedText: String? = null,
        block: () -> T,
    ): T {
        if (closed.get()) throw closedFailure()
        return try {
            thread.run { translatingFailures(action, typedText, block) }
        } catch (e: CancellationException) {
            // A session closed while this call was being dispatched rejects it; the caller itself was not cancelled.
            if (closed.get() && currentCoroutineContext().isActive) throw closedFailure() else throw e
        }
    }

    private fun closedFailure() = BrowserActionException("browser session '$label' is closed")

    private fun elementByRef(ref: Int): Locator {
        val element = page.locator("[$REF_ATTRIBUTE=\"$ref\"]")
        if (element.count() == 0) throw BrowserActionException("element $ref not found, take a new snapshot")
        return element.first()
    }

    private fun firstVisible(selector: String): Locator = page.locator(selector).filter(visibleOnly()).first()

    private fun selectOption(
        element: Locator,
        option: String,
    ) {
        val resolution = element.evaluate(BundledScripts.resolveOption, option) as? Map<*, *> ?: emptyMap<String, Any>()
        val index = (resolution["index"] as? Number)?.toInt()
        if (index == null) {
            val reason = resolution["error"] as? String
            if (reason != null) throw BrowserActionException("cannot select \"$option\": $reason")
            val available = (resolution["available"] as? List<*>).orEmpty().joinToString(", ") { "\"$it\"" }
            throw BrowserActionException("option \"$option\" not found; available options: $available")
        }
        element.selectOption(SelectOption().setIndex(index))
    }

    private fun probe(target: Map<String, String>): Boolean =
        surviveNavigation { page.evaluate(BundledScripts.visibilityProbe, target) == true }

    /**
     * Runs a page evaluation; when a navigation (e.g. one started by the previous click) replaces the document
     * meanwhile, waits for the new document to load and evaluates once more instead of failing the agent's step.
     */
    private fun <T> surviveNavigation(evaluation: () -> T): T =
        try {
            evaluation()
        } catch (e: PlaywrightException) {
            if (e.message?.contains(CONTEXT_DESTROYED) != true) throw e
            page.waitForLoadState()
            evaluation()
        }

    /**
     * Waits with [BundledScripts.visibilityProbe] polled inside the page every [PROBE_POLLING_INTERVAL_MS], so
     * [WaitOutcome.observedAt] lags the moment of visibility by at most one interval plus one round trip.
     * Playwright re-runs the probe in the new document when the page navigates meanwhile.
     */
    private fun awaitProbe(
        target: Map<String, String>,
        timeout: Duration,
    ): WaitOutcome {
        if (!timeout.isPositive()) return if (probe(target)) WaitOutcome(true, clock.now()) else WaitOutcome(false, null)
        return try {
            val options = Page.WaitForFunctionOptions().setPollingInterval(PROBE_POLLING_INTERVAL_MS).setTimeout(timeoutMillis(timeout))
            val handle = page.waitForFunction(BundledScripts.visibilityProbe, target, options)
            val observedAt = clock.now()
            handle.dispose()
            WaitOutcome(true, observedAt)
        } catch (e: TimeoutError) {
            logger.debug { "wait in session '$label' timed out after $timeout" }
            WaitOutcome(false, null)
        }
    }

    /** Fallback for Playwright-only selector syntax; its precision follows Playwright's locator retry schedule. */
    private fun awaitLocator(
        target: Locator,
        timeout: Duration,
    ): WaitOutcome {
        val candidate = target.filter(visibleOnly()).first()
        if (!timeout.isPositive()) return if (candidate.count() > 0) WaitOutcome(true, clock.now()) else WaitOutcome(false, null)
        return try {
            candidate.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMillis(timeout)))
            WaitOutcome(true, clock.now())
        } catch (e: TimeoutError) {
            logger.debug { "wait in session '$label' timed out after $timeout" }
            WaitOutcome(false, null)
        }
    }

    /** Playwright treats 0 as "no timeout", so a positive wait never goes below 1 ms. */
    private fun timeoutMillis(timeout: Duration): Double = timeout.inWholeMilliseconds.coerceAtLeast(1).toDouble()

    /** The current values of the page's secret fields, for [SecretRedactor]; see [BundledScripts.secretValues]. */
    private fun secretValues(): List<String> = (page.evaluate(BundledScripts.secretValues) as? List<*>).orEmpty().filterIsInstance<String>()

    companion object {
        /** Attribute the snapshot writes on every numbered element. */
        const val REF_ATTRIBUTE = "data-petek-ref"

        /** How often a wait re-checks the page: the resolution of every measured latency (t1). */
        const val PROBE_POLLING_INTERVAL_MS = 50.0

        /** Playwright's message when the document an evaluation ran in was replaced by a navigation. */
        private const val CONTEXT_DESTROYED = "Execution context was destroyed"

        /**
         * Opens a session: creates its thread, then on that thread its Playwright instance, browser (via
         * [connector]), context and page. Everything created so far is released again when any step fails, or when
         * the caller is cancelled meanwhile. [onClosed] is called once the session has been closed.
         */
        suspend fun open(
            options: SessionOptions,
            connector: BrowserConnector,
            clock: HarnessClock,
            onClosed: (PlaywrightBrowserSession) -> Unit = {},
        ): PlaywrightBrowserSession {
            options.storageState?.let { state ->
                if (!state.exists()) throw BrowserActionException("storage state file $state does not exist")
            }
            val thread = ConfinedThread("browser-${options.label}")
            val traffic = RealtimeTrafficRecorder()
            val handles =
                try {
                    thread.runToCompletion { PlaywrightHandles.create(options, connector, traffic, clock) }
                } catch (e: BrowserActionException) {
                    thread.close()
                    throw e
                } catch (e: Exception) {
                    thread.close()
                    val reason = if (e is PlaywrightException) PlaywrightFailures.reasonOf(e.message.orEmpty()) else e.message
                    throw BrowserActionException("could not open browser session '${options.label}': $reason", e)
                }
            val session = PlaywrightBrowserSession(options, thread, clock, handles, traffic, onClosed)
            try {
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                session.close()
                throw e
            }
            logger.debug { "browser session '${options.label}' opened ($connector)" }
            return session
        }

        private fun visibleOnly(): Locator.FilterOptions = Locator.FilterOptions().setVisible(true)
    }
}
