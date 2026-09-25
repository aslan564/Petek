/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.ObservedMutation
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.SessionOptions
import az.petek.browser.domain.WaitOutcome
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * [BrowserSession] on Playwright: one agent's isolated browser context (own cookies, localStorage and
 * sessionStorage) with a single page, created by the session's own [Playwright] instance on its own thread named
 * `browser-<label>`. Every Playwright object is created and used only on that thread (CLAUDE.md rule 9); the
 * suspend functions may be called from any coroutine and run one at a time, in call order. A cancelled caller (step
 * timeout, watchdog) is released at once; a browser call it already started finishes on the thread first.
 *
 * Contract details the callers rely on:
 * - Element refs come from the latest [snapshot] (`data-petek-ref` attributes); a stale ref fails with
 *   "element N not found, take a new snapshot" instead of acting on the wrong element.
 * - Selector functions act on the first *visible* match, so a hidden duplicate (mobile menu, template) is skipped.
 * - [waitForText] and [waitForSelector] never throw on timeout: they return `found = false`. On success,
 *   [WaitOutcome.observedAt] is taken from the harness clock right after the page reported the text or element
 *   visible (rule 1). The check is polled *inside* the page every [PROBE_POLLING_INTERVAL_MS] ms rather than with
 *   `getByText(...).waitFor()`, whose retries back off to 500 ms and would blur a real-time latency (t1 − t0).
 *   Text matching follows `getByText`: case-insensitive, whitespace-normalized substring of the rendered text,
 *   open shadow roots included.
 *   Selectors that are plain CSS are probed the same way; Playwright-only syntax (`text=…`) uses a locator wait.
 * - [navigate] opens web pages only (http(s) URLs, paths against the base URL, `about:blank`); see [requireWebAddress].
 * - [request] does not follow redirects, so an `http_status` assertion sees the endpoint's own status.
 * - JavaScript dialogs (`alert`, `confirm`, `prompt`, `beforeunload`) are accepted as they open and kept until
 *   [drainDialogs] reports them; see [PlaywrightHandles].
 * - Mutating requests the page sends to the target's origin (form posts, `fetch`, XHR) are recorded with their
 *   answer's status and the harness time the session saw it, for [mutations]; see [MutationRecorder].
 * - Password values never leave the adapter: snapshots show `******`, DOM and ARIA snapshots are redacted, and
 *   text typed with [fill] is masked in error messages. Text typed into a password field is remembered and masked
 *   in every later snapshot, [readText] and [currentUrl], even after the page reveals the field or echoes the value.
 * - Failures surface as [BrowserActionException] with a short reason. After [close], calls fail the same way.
 */
internal class PlaywrightBrowserSession private constructor(
    private val options: SessionOptions,
    private val thread: ConfinedThread,
    private val clock: HarnessClock,
    private val handles: PlaywrightHandles,
    private val traffic: RealtimeTrafficRecorder,
    private val dialogs: DialogRecorder,
    private val mutations: MutationRecorder,
    private val onClosed: (PlaywrightBrowserSession) -> Unit,
) : BrowserSession {
    private val closed = AtomicBoolean(false)
    private val page: Page get() = handles.page

    /** Text this session typed into secret fields; masked in everything read back later. Session thread only. */
    private val typedSecrets = LinkedHashSet<String>()

    override val label: String get() = options.label

    /** Name of the thread every Playwright call of this session runs on. */
    val threadName: String get() = thread.name

    override suspend fun navigate(pathOrUrl: String) {
        requireWebAddress(pathOrUrl)
        perform("navigate to $pathOrUrl") { page.navigate(pathOrUrl) }
    }

    override suspend fun snapshot(): PageSnapshot =
        perform("snapshot") {
            val snapshot =
                surviveNavigation {
                    SnapshotParser.parse(page.evaluate(BundledScripts.pageIndexer, SnapshotLimits().asScriptArgument()))
                }
            SecretRedactor.redactSnapshot(snapshot, typedSecrets)
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
            fillInto(element, text)
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
        perform("fill $selector", typedText = text) { fillInto(firstVisible(selector), text) }
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
            if (matches.count() == 0) null else SecretRedactor.redactText(matches.first().innerText().trim(), typedSecrets)
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
            if (surviveNavigation { page.evaluate(BundledScripts.isCssSelector, selector) } == true) {
                awaitProbe(mapOf("selector" to selector), timeout)
            } else {
                awaitLocator(page.locator(selector), timeout)
            }
        }

    override suspend fun isTextVisible(text: String): Boolean = perform("check text \"$text\"") { probe(mapOf("text" to text)) }

    override suspend fun isSelectorVisible(selector: String): Boolean =
        perform("check $selector") { page.locator(selector).filter(visibleOnly()).count() > 0 }

    override suspend fun count(selector: String): Int = perform("count $selector") { page.locator(selector).count() }

    override suspend fun currentUrl(): String = perform("read the URL") { SecretRedactor.redactText(page.url(), typedSecrets) }

    override suspend fun screenshot(): ByteArray = perform("screenshot") { page.screenshot() }

    override suspend fun accessibilitySnapshot(): String =
        perform("accessibility snapshot") {
            surviveNavigation {
                val (aria, secrets) = withSecretValues { page.locator("body").ariaSnapshot() }
                SecretRedactor.redactAriaSnapshot(aria, secrets)
            }
        }

    override suspend fun domSnapshot(): String =
        perform("DOM snapshot") {
            surviveNavigation {
                val (html, secrets) = withSecretValues { page.evaluate(BundledScripts.domSnapshot) as? String ?: "" }
                SecretRedactor.redactText(html, secrets)
            }
        }

    override suspend fun saveStorageState(path: Path) {
        perform("save storage state") {
            // The file holds live session cookies: only this user may read the directory and the file (POSIX; a no-op elsewhere).
            path.toAbsolutePath().parent?.let { directory ->
                Files.createDirectories(directory)
                restrictToOwner(directory, OWNER_ONLY_DIRECTORY)
            }
            handles.context.storageState(BrowserContext.StorageStateOptions().setPath(path))
            restrictToOwner(path, OWNER_ONLY_FILE)
        }
    }

    private fun restrictToOwner(
        path: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows): the user's own profile directory protects the file.
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

    override suspend fun drainDialogs(): List<DialogEvent> =
        perform("read dialogs") {
            // As above: the round trip dispatches a dialog event already received, so the handler answers it first.
            runCatching { page.title() }
            dialogs.drain { message -> SecretRedactor.redactText(message, typedSecrets) }
        }

    override suspend fun mutations(since: HarnessTimestamp): List<ObservedMutation> =
        perform("read the page's requests") {
            // As above: the round trip dispatches answers already received, so they are recorded (and timed) first.
            runCatching { page.title() }
            mutations.since(since)
        }

    /**
     * Idempotent. Releases the context, the browser connection (or own browser) and the Playwright driver once the
     * call already running on the session thread (if any) has finished; calls still queued fail as closed.
     */
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            thread.runToCompletion { handles.release() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { thread.close() }
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
            thread.run {
                // Queued before close() but reached only after it: the handles are being released.
                if (closed.get()) throw closedFailure()
                translatingFailures(action, typedText, block)
            }
        } catch (e: RejectedExecutionException) {
            throw closedFailure()
        }
    }

    private fun closedFailure() = BrowserActionException("browser session '$label' is closed")

    /**
     * Refuses addresses that are not web pages. A `file:` (or `chrome:`, `view-source:`, …) address would put local
     * files such as `.env` into snapshots that reach the LLM (CLAUDE.md rule 10), e.g. when a page talks an agent
     * into opening one. The address is read the way the browser's URL parser reads it: surrounding control
     * characters and spaces are ignored and tabs or line breaks inside it are dropped.
     */
    private fun requireWebAddress(pathOrUrl: String) {
        val normalized = pathOrUrl.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        val scheme =
            URL_SCHEME
                .find(normalized)
                ?.groupValues
                ?.get(1)
                ?.lowercase() ?: return
        if (scheme in WEB_SCHEMES || normalized.equals(BLANK_PAGE, ignoreCase = true)) return
        throw BrowserActionException("cannot open \"$pathOrUrl\": only http(s) addresses and paths are allowed")
    }

    private fun elementByRef(ref: Int): Locator {
        val element = page.locator("[$REF_ATTRIBUTE=\"$ref\"]")
        if (element.count() == 0) throw BrowserActionException("element $ref not found, take a new snapshot")
        return element.first()
    }

    private fun firstVisible(selector: String): Locator = page.locator(selector).filter(visibleOnly()).first()

    /**
     * Fills [element], remembering [text] as a secret first when the element is a password field: once typed, a
     * password stays masked even if the page later reveals the field ("show password") or echoes the value.
     */
    private fun fillInto(
        element: Locator,
        text: String,
    ) {
        if (text.isNotEmpty() && element.evaluate(BundledScripts.isSecretField) == true) typedSecrets += text
        element.fill(text)
    }

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
            val limit = timeout.toPlaywrightTimeout()
            val options = Page.WaitForFunctionOptions().setPollingInterval(PROBE_POLLING_INTERVAL_MS).setTimeout(limit)
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
            candidate.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeout.toPlaywrightTimeout()))
            WaitOutcome(true, clock.now())
        } catch (e: TimeoutError) {
            logger.debug { "wait in session '$label' timed out after $timeout" }
            WaitOutcome(false, null)
        }
    }

    /**
     * Captures text from the page together with the values its secret fields had before *and* after the capture
     * (see [BundledScripts.secretValues]), so a script clearing a password field meanwhile (as sign-in forms do on
     * submit) cannot leave the typed value unmasked in the captured text.
     */
    private fun <T> withSecretValues(capture: () -> T): Pair<T, Set<String>> {
        val before = secretValues()
        val captured = capture()
        return captured to typedSecrets + before + secretValues()
    }

    private fun secretValues(): List<String> = (page.evaluate(BundledScripts.secretValues) as? List<*>).orEmpty().filterIsInstance<String>()

    companion object {
        /** Attribute the snapshot writes on every numbered element. */
        const val REF_ATTRIBUTE = "data-petek-ref"

        /** How often a wait re-checks the page: the resolution of every measured latency (t1). */
        const val PROBE_POLLING_INTERVAL_MS = 50.0

        private val URL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
        private val WEB_SCHEMES = setOf("http", "https")
        private const val BLANK_PAGE = "about:blank"

        /** Playwright's message when the document an evaluation ran in was replaced by a navigation. */
        private const val CONTEXT_DESTROYED = "Execution context was destroyed"

        /** `rw-------`: a saved storage state holds live session cookies. */
        private val OWNER_ONLY_FILE: Set<PosixFilePermission> = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /** `rwx------` for the directory that holds them. */
        private val OWNER_ONLY_DIRECTORY: Set<PosixFilePermission> = OWNER_ONLY_FILE + PosixFilePermission.OWNER_EXECUTE

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
            val dialogs = DialogRecorder()
            val mutations = MutationRecorder(options.baseUrl)
            val handles =
                try {
                    thread.runToCompletion { PlaywrightHandles.create(options, connector, traffic, dialogs, mutations, clock) }
                } catch (e: BrowserActionException) {
                    thread.close()
                    throw e
                } catch (e: Exception) {
                    thread.close()
                    val reason = if (e is PlaywrightException) PlaywrightFailures.reasonOf(e.message.orEmpty()) else e.message
                    throw BrowserActionException("could not open browser session '${options.label}': $reason", e)
                }
            val session = PlaywrightBrowserSession(options, thread, clock, handles, traffic, dialogs, mutations, onClosed)
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
