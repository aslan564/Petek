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
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.LookRequest
import az.petek.browser.domain.LookShot
import az.petek.browser.domain.LookShotKind
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.ObservedMutation
import az.petek.browser.domain.PageFacts
import az.petek.browser.domain.PageHealth
import az.petek.browser.domain.PageLook
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.PageTiming
import az.petek.browser.domain.SessionOptions
import az.petek.browser.domain.TextWatch
import az.petek.browser.domain.Viewport
import az.petek.browser.domain.WaitOutcome
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import com.google.gson.JsonObject
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.RequestOptions
import com.microsoft.playwright.options.ScreenshotAnimations
import com.microsoft.playwright.options.ScreenshotCaret
import com.microsoft.playwright.options.ScreenshotScale
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.SelectOption
import com.microsoft.playwright.options.WaitForSelectorState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.io.path.exists
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

private val logger = KotlinLogging.logger {}

/**
 * [BrowserSession] on Playwright: one agent's isolated browser context (own cookies, localStorage and
 * sessionStorage) with a single page, created by the session's own [Playwright] instance on its own thread named
 * `browser-<label>`. Every Playwright object is created and used only on that thread (AGENTS.md rule 9); the
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
 * - [watchText] leaves a watch in the page (`text-watch.js`): the same text match, run on every DOM change (at most every
 *   [WATCH_GAP_MS] ms) and every [PROBE_POLLING_INTERVAL_MS] ms, timed with the page's monotonic clock. [stopTextWatch]
 *   turns that time into harness time from the middle of the round trip that started the watch, so t1 is off by at
 *   most half that round trip plus one check. A navigation or reload ends the watch with the document it lived in.
 * - [navigate] opens web pages only (http(s) URLs, paths against the base URL, `about:blank`); see [requireWebAddress].
 * - [request] does not follow redirects, so an `http_status` assertion sees the endpoint's own status. It carries the
 *   session's cookies and, to the target's origin (and the site's API host, [SessionOptions.apiOrigin]) only, the
 *   credential headers the page itself sent there ([PageCredentials]: a token the page keeps and sends as
 *   `Authorization`, a CSRF header), so a site that signs its calls with a token answers the probe as it answers the page.
 * - JavaScript dialogs (`alert`, `confirm`, `prompt`, `beforeunload`) are accepted as they open and kept until
 *   [drainDialogs] reports them; see [PlaywrightHandles].
 * - Mutating requests the page sends to the target's origin (form posts, `fetch`, XHR) are recorded with their
 *   answer's status and the harness time the session saw it, for [mutations]; see [MutationRecorder].
 * - Password values never leave the adapter: snapshots show `******`, DOM and ARIA snapshots are redacted, and
 *   text typed with [fill] is masked in error messages. Text typed into a password field is remembered and masked
 *   in every later snapshot, [readText] and [currentUrl], even after the page reveals the field or echoes the value.
 *   The page's own credentials are masked the same way.
 * - [look] takes frames of the page by code for comparing releases: it scrolls, moves the pointer, blurs the focus and
 *   clears the selection, but adds nothing to the page (masks are measured, never painted), and leaves it at its top.
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
    private val health: HealthRecorder,
    private val credentials: PageCredentials,
    private val onClosed: (PlaywrightBrowserSession) -> Unit,
) : BrowserSession {
    private val closed = AtomicBoolean(false)
    private val page: Page get() = handles.page

    /** Text this session typed into secret fields; masked in everything read back later. Session thread only. */
    private val typedSecrets = LinkedHashSet<String>()

    /** What is masked in everything read back: typed secrets and the page's own credentials. Session thread only. */
    private fun masked(): Set<String> = typedSecrets + credentials.secrets()

    /** Where each running text watch began, to turn the page's times into harness times. Session thread only. */
    private val watchOrigins = HashMap<String, WatchOrigin>()

    /** The saved state's sessionStorage not put back yet, by origin ([SessionStorageState]). Session thread only. */
    private val pendingSessionStorage: MutableMap<String, List<List<String>>> by lazy(LazyThreadSafetyMode.NONE) {
        options.storageState?.let { SessionStorageState.read(it).toMutableMap() } ?: mutableMapOf()
    }

    override val label: String get() = options.label

    /** Name of the thread every Playwright call of this session runs on. */
    val threadName: String get() = thread.name

    override suspend fun navigate(pathOrUrl: String) {
        requireWebAddress(pathOrUrl)
        perform("navigate to $pathOrUrl") {
            restoreSessionStorage(pathOrUrl)
            page.navigate(pathOrUrl)
        }
    }

    /**
     * Puts the saved state's sessionStorage of [pathOrUrl]'s origin back into this tab before the site's first page there
     * loads: an empty page of that origin, answered by the session itself (the site never sees the request), receives
     * the entries, and the real navigation follows in the same tab. Once per origin. Session thread only.
     */
    private fun restoreSessionStorage(pathOrUrl: String) {
        if (pendingSessionStorage.isEmpty()) return
        val target = runCatching { options.baseUrl.resolve(pathOrUrl.trim()) }.getOrNull() ?: return
        if (target.scheme?.lowercase() !in WEB_SCHEMES || target.host == null) return
        val origin = LocalStorageSeed.originOf(target)
        val entries = pendingSessionStorage.remove(origin) ?: return
        val blank = "$origin/"
        val answer =
            Consumer<Route> { route ->
                route.fulfill(
                    Route
                        .FulfillOptions()
                        .setStatus(HTTP_OK)
                        .setContentType("text/html")
                        .setBody(SessionStorageState.BLANK_PAGE),
                )
            }
        page.route(blank, answer)
        try {
            page.navigate(blank)
            page.evaluate(SessionStorageState.RESTORE, entries)
        } finally {
            page.unroute(blank, answer)
        }
    }

    override suspend fun snapshot(): PageSnapshot =
        perform("snapshot") {
            val snapshot =
                surviveNavigation {
                    SnapshotParser.parse(page.evaluate(BundledScripts.pageIndexer, SnapshotLimits().asScriptArgument()))
                }
            SecretRedactor.redactSnapshot(snapshot, masked())
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
            if (matches.count() == 0) null else SecretRedactor.redactText(matches.first().innerText().trim(), masked())
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

    override suspend fun watchText(
        key: String,
        text: String,
    ): TextWatch =
        perform("watch for text \"$text\"") {
            val arguments =
                mapOf(
                    "key" to key,
                    "text" to text,
                    "gapMs" to WATCH_GAP_MS,
                    "pollMs" to PROBE_POLLING_INTERVAL_MS,
                    "maxMs" to WATCH_MAX_MS,
                )
            val before = clock.now()
            val armed = surviveNavigation { page.evaluate(BundledScripts.textWatch, arguments) } as? Map<*, *>
            val after = clock.now()
            val armedAt = (armed?.get("armedAt") as? Number)?.toDouble() ?: return@perform TextWatch.Lost
            // The page took its time somewhere inside this round trip: its middle is the harness time closest to it.
            watchOrigins[key] = WatchOrigin(armedAt, before + before.elapsedUntil(after) / 2)
            if (armed["before"] == true) TextWatch.WasThere else TextWatch.NotYet
        }

    override suspend fun stopTextWatch(key: String): TextWatch =
        perform("read the text watch") {
            val origin = watchOrigins.remove(key)
            val state = surviveNavigation { page.evaluate(BundledScripts.textWatchRead, mapOf("key" to key)) } as? Map<*, *>
            when {
                origin == null || state == null -> TextWatch.Lost
                state["before"] == true -> TextWatch.WasThere
                else -> (state["seenAt"] as? Number)?.let { TextWatch.Seen(origin.harnessTime(it.toDouble())) } ?: TextWatch.NotYet
            }
        }

    override suspend fun isSelectorVisible(selector: String): Boolean =
        perform("check $selector") { page.locator(selector).filter(visibleOnly()).count() > 0 }

    override suspend fun count(selector: String): Int = perform("count $selector") { page.locator(selector).count() }

    override suspend fun currentUrl(): String = perform("read the URL") { SecretRedactor.redactText(page.url(), masked()) }

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

    override suspend fun setCorrelationId(id: String?) {
        if (!options.correlationHeader) return
        perform("set the correlation header") {
            handles.context.setExtraHTTPHeaders(if (id == null) emptyMap() else mapOf(CORRELATION_HEADER to id))
        }
    }

    override suspend fun saveStorageState(path: Path) {
        perform("save storage state") {
            // The file holds live session cookies: only this user may read the directory and the file (POSIX; a no-op elsewhere).
            path.toAbsolutePath().parent?.let { directory ->
                Files.createDirectories(directory)
                restrictToOwner(directory, OWNER_ONLY_DIRECTORY)
            }
            // Entries not put back yet (no page of their origin opened) stay; what the tabs hold now wins. Read before
            // Playwright writes, since [path] may be the very file this session was opened with.
            val notRestored = pendingSessionStorage.toMap()
            handles.context.storageState(BrowserContext.StorageStateOptions().setPath(path))
            SessionStorageState.write(path, notRestored + capturedSessionStorage())
            restrictToOwner(path, OWNER_ONLY_FILE)
        }
    }

    /** Every open tab's sessionStorage by origin. Session thread only. */
    private fun capturedSessionStorage(): Map<String, List<List<String>>> =
        handles.context
            .pages()
            .mapNotNull { tab ->
                val found = runCatching { tab.evaluate(SessionStorageState.CAPTURE) }.getOrNull() as? Map<*, *> ?: return@mapNotNull null
                val origin = found["origin"] as? String ?: return@mapNotNull null
                val entries =
                    (found["entries"] as? List<*>).orEmpty().mapNotNull { pair ->
                        (pair as? List<*>)?.takeIf { it.size == 2 }?.map { it.toString() }
                    }
                origin to entries
            }.toMap()

    private fun restrictToOwner(
        path: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows): the user's own profile directory protects the file.
        } catch (e: IOException) {
            // Best effort: the state was saved; a mount that refuses chmod must not fail the tester's login.
            logger.warn { "Could not restrict the permissions of $path (${e::class.simpleName}: ${e.message})" }
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
            // The page's own token goes with it, as the page sends it (never elsewhere, never written down). An address
            // the URI parser cannot read gets none; Playwright then says what is wrong with it.
            val own = runCatching { options.baseUrl.resolve(path.trim()) }.getOrNull()?.let(credentials::headersFor).orEmpty()
            own.forEach { (name, value) -> request.setHeader(name, value) }
            val response =
                try {
                    handles.context.request().fetch(path, request)
                } catch (e: PlaywrightException) {
                    // The call's log names every header it sent: never let the page's own token or the cookies out.
                    throw PlaywrightFailures.describe("${method.uppercase()} $path", e, null, masked() + own.values, keepCause = false)
                }
            try {
                HttpProbeResult(response.status(), SecretRedactor.redactText(response.text(), masked()), own.keys)
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
            dialogs.drain { message -> SecretRedactor.redactText(message, masked()) }
        }

    override suspend fun mutations(since: HarnessTimestamp): List<ObservedMutation> =
        perform("read the page's requests") {
            // As above: the round trip dispatches answers already received, so they are recorded (and timed) first.
            runCatching { page.title() }
            mutations.since(since)
        }

    override suspend fun health(
        since: HarnessTimestamp,
        slowAfter: Duration,
    ): PageHealth =
        perform("read the page's health") {
            // As above: the round trip dispatches events already received, so they are recorded first.
            runCatching { page.title() }
            health.since(since, slowAfter) { SecretRedactor.redactText(it, masked()) }
        }

    override suspend fun links(): List<String> =
        perform("read the page's links") {
            surviveNavigation {
                @Suppress("UNCHECKED_CAST")
                val hrefs = page.evaluate(LINKS_SCRIPT) as? List<String> ?: emptyList()
                val base = URI(page.url())
                hrefs
                    .mapNotNull { href -> runCatching { base.resolve(href.trim()) }.getOrNull() }
                    .filter { sameOrigin(it, options.baseUrl) }
                    .map { (it.rawPath.orEmpty().ifEmpty { "/" }) + (it.rawQuery?.let { query -> "?$query" } ?: "") }
                    .distinct()
            }
        }

    override suspend fun goBack(): Boolean = perform("go back") { page.goBack() != null }

    override suspend fun pageFacts(): PageFacts? =
        perform("read the page's facts") {
            surviveNavigation {
                (page.evaluate(BundledScripts.pageFacts) as? Map<*, *>)?.let {
                    PageFactsReading.of(it) { text -> SecretRedactor.redactText(text, masked()) }
                }
            }
        }

    override suspend fun pageTiming(): PageTiming? =
        perform("read the page's timing") {
            surviveNavigation {
                (page.evaluate(BundledScripts.pageTiming) as? Map<*, *>)?.let { raw ->
                    fun long(key: String) = (raw[key] as? Number)?.toLong()
                    PageTiming(
                        long("ttfb"),
                        long("domContentLoaded"),
                        long("load"),
                        long("largestPaint"),
                        (raw["layoutShift"] as? Number)?.toDouble(),
                    )
                }
            }
        }

    /**
     * A look of the current page ([LookRequest]), one load at a time ([lookOnce]); the second load opens the page's
     * address again. The look's facts are those read with the first load's final frame ([LookShotKind.MAIN]); it settled
     * when every load did. Null when the page went to another address during a load, or the second load did not show
     * the first one's page (it landed elsewhere, or the site answered it with another status): its frame would pass for
     * the page drawn again. The page is then left where it went.
     */
    override suspend fun look(request: LookRequest): PageLook? {
        val first = lookOnce(request, reload = false) ?: return null
        val again = if (request.loads > 1) lookOnce(request, reload = true) ?: return null else null
        if (again != null && !again.showsPageOf(first)) return null
        val main = first.shot(first.final, LookShotKind.MAIN)
        val shots =
            buildList {
                add(main)
                first.moved?.let { add(first.shot(it, LookShotKind.MOVED)) }
                again?.takeUnless { it.final.contentEquals(first.final) }?.let { add(it.shot(it.final, LookShotKind.RELOADED)) }
            }
        val loads = listOfNotNull(first, again)
        return PageLook(
            shots = shots,
            viewport = first.viewport,
            pageHeight = first.facts.pageHeight,
            landedPath = first.facts.path,
            status = first.facts.status,
            renderer = first.renderer,
            settled = loads.all { it.unsettled.isEmpty() },
            unsettled = UNSETTLED.filter { what -> loads.any { what in it.unsettled } },
            fonts = first.facts.fonts,
            anchors = LookReading.anchors(first.facts, main.width, main.height),
            rejectedSelectors = loads.flatMap { it.facts.rejectedSelectors }.distinct(),
        )
    }

    /**
     * One load of a look:
     * 1. (second load: the page's address opened again, [loadAgain]) the network idle for a moment (bounded: a page that
     *    polls never is), the pointer parked in the corner, the page brought to rest by `look-settle.js`, and then drawn
     *    as a full-page frame draws it ([drawAsBeyondTheScreen]): after settling, so a document the page went to while
     *    it settled is drawn so too;
     * 2. frames [LOOK_FRAME_GAP] apart until two in a row are the same bytes (Chromium encodes the same pixels to the
     *    same PNG), at most three: the last is the load's frame, and when the third still differs from the second, the
     *    second is kept as the earlier frame of a page that keeps moving by itself;
     * 3. the page read by `look-read.js` (facts, areas and anchors) with that frame.
     * The waits between frames run outside the session thread. Null when the page went to another address meanwhile:
     * after settling, or (second load) since it was opened again.
     */
    private suspend fun lookOnce(
        request: LookRequest,
        reload: Boolean,
    ): LookLoad? {
        val settled =
            perform("settle the page for a look") {
                // A second load that a redirect, or the page itself, takes to another address is no load of this page.
                val opened = if (reload) withoutQuery(page.url()).also { loadAgain() } else null
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(NETWORK_IDLE_WAIT_MS))
                } catch (_: TimeoutError) {
                    // A page that polls never goes idle; the settle script's quiet window tells whether it calmed down.
                }
                page.mouse().move(0.0, 0.0)
                val arguments =
                    mapOf(
                        "settleMs" to request.settle.inWholeMilliseconds.toDouble(),
                        "maxHeight" to request.maxHeight,
                        "fontsMs" to LOOK_FONTS_WAIT_MS,
                        "quietMs" to LOOK_QUIET_MS,
                        "stepMs" to LOOK_SCROLL_STEP_MS,
                    )
                val raw = surviveNavigation { page.evaluate(BundledScripts.lookSettle, arguments) } as? Map<*, *> ?: emptyMap<String, Any>()
                // Taken before the drawing below: a document the page goes to from here on is not drawn so, and is found
                // at another address when the look is read.
                val address = page.url()
                if (opened != null && withoutQuery(address) != opened) return@perform null
                // Only the raster changes, not the layout, so what the settle script measured holds.
                drawAsBeyondTheScreen()

                fun number(key: String) = (raw[key] as? Number)?.toInt() ?: 0
                LookSettled(
                    unsettled =
                        listOfNotNull(
                            "network".takeUnless { raw["quiet"] == true },
                            "fonts".takeUnless { raw["fontsReady"] == true },
                            "images".takeIf { number("pendingImages") > 0 },
                        ),
                    pageHeight = number("pageHeight"),
                    viewport = page.viewportSize()?.let { Viewport(it.width, it.height) } ?: Viewport(number("width"), number("height")),
                    address = address,
                )
            } ?: return null
        val options = lookShotOptions(request.maxHeight, settled.viewport, settled.pageHeight)
        val first = perform("take a look") { page.screenshot(options) }
        delay(LOOK_FRAME_GAP)
        var final = perform("take a look") { page.screenshot(options) }
        var moved: ByteArray? = null
        if (!final.contentEquals(first)) {
            delay(LOOK_FRAME_GAP)
            val third = perform("take a look") { page.screenshot(options) }
            if (!third.contentEquals(final)) moved = final
            final = third
        }
        val captured = PngSize.of(final) ?: throw BrowserActionException("a look's frame is not a PNG")
        return perform("read the look") {
            val arguments = lookReadArguments(request, captured.height)
            val raw =
                try {
                    surviveNavigation { page.evaluate(BundledScripts.lookRead, arguments) }
                } catch (e: PlaywrightException) {
                    // The arguments hold the run's own texts: none of them leaves with the failure.
                    val secrets = request.runTexts.map { it.text } + masked()
                    throw PlaywrightFailures.describe("read the look", e, null, secrets, keepCause = false)
                }
            if (withoutQuery(page.url()) != withoutQuery(settled.address)) return@perform null
            val facts = LookReading.facts(raw as? Map<*, *> ?: return@perform null) { SecretRedactor.redactText(it, masked()) }
            LookLoad(final, moved, facts, settled.unsettled, settled.viewport, renderer(facts.userAgent))
        }
    }

    /**
     * Chromium draws a document differently once a frame beyond the screen was taken of it (on macOS, its text: the
     * layout stays as it was), and keeps drawing it so until another document loads: a full-page frame of a page taller
     * than the screen would differ from the frame of one that fits it, or of the first screen only. A one-pixel capture
     * beyond the screen of the document about to be framed draws every look's frames the same way. Its picture is not
     * kept. Session thread only.
     */
    private fun drawAsBeyondTheScreen() {
        val devtools = handles.context.newCDPSession(page)
        try {
            val clip =
                JsonObject().apply {
                    addProperty("x", 0)
                    addProperty("y", 0)
                    addProperty("width", 1)
                    addProperty("height", 1)
                    addProperty("scale", 1)
                }
            devtools.send(
                "Page.captureScreenshot",
                JsonObject().apply {
                    addProperty("captureBeyondViewport", true)
                    add("clip", clip)
                },
            )
        } finally {
            runCatching { devtools.detach() }
        }
    }

    /**
     * Opens the current page's address again, as a visitor coming back would: a plain GET, so a page that answered a form
     * post is never posted again (a reload would). An address with a fragment is reloaded instead, since opening it again
     * would only scroll the same document. Session thread only.
     */
    private fun loadAgain() {
        val address = page.url()
        val scheme =
            URL_SCHEME
                .find(address)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        if (scheme !in WEB_SCHEMES) throw BrowserActionException("cannot open the page again for a look: it is not a web page")
        try {
            if ('#' in address) {
                page.reload(Page.ReloadOptions().setTimeout(LOOK_LOAD_TIMEOUT_MS))
            } else {
                page.navigate(address, Page.NavigateOptions().setTimeout(LOOK_LOAD_TIMEOUT_MS))
            }
        } catch (e: PlaywrightException) {
            // The failure names the address, which may hold what the tester typed (a form sent by GET).
            throw PlaywrightFailures.describe("open the page again for a look", e, null, masked(), keepCause = false)
        }
    }

    /**
     * A look's frame: PNG at CSS scale (one image pixel per CSS pixel), the whole page down to the capture height (at least
     * the first screen; only the first screen when [maxHeight] is 0), finite CSS animations finished and infinite ones
     * started over, transitions off and the caret hidden by [LOOK_STYLE], a style that applies only while the frame is
     * taken. Not Playwright's own caret hiding: it writes the caret colour into every field's `style` attribute and leaves
     * `style=""` behind on fields that had none.
     */
    private fun lookShotOptions(
        maxHeight: Int,
        viewport: Viewport,
        pageHeight: Int,
    ): Page.ScreenshotOptions =
        Page
            .ScreenshotOptions()
            .setType(ScreenshotType.PNG)
            .setAnimations(ScreenshotAnimations.DISABLED)
            .setCaret(ScreenshotCaret.INITIAL)
            .setScale(ScreenshotScale.CSS)
            .setStyle(LOOK_STYLE)
            .setTimeout(LOOK_LOAD_TIMEOUT_MS)
            .apply {
                if (maxHeight > 0) {
                    setFullPage(true)
                    setClip(0.0, 0.0, viewport.width.toDouble(), max(viewport.height, min(pageHeight, maxHeight)).toDouble())
                }
            }

    private fun lookReadArguments(
        request: LookRequest,
        captureHeight: Int,
    ): Map<String, Any> =
        mapOf(
            "captureHeight" to captureHeight,
            "selectors" to
                request.selectors.map {
                    mapOf("css" to it.css, "source" to it.source, "reason" to LookReading.selectorReason(it.source).name)
                },
            "runTexts" to request.runTexts.map { mapOf("kind" to it.kind, "text" to it.text) },
            "runTag" to request.runTag.orEmpty(),
            "maxAreas" to LookReading.SCRIPT_MAX_AREAS,
            "maxPerSelector" to LookReading.MAX_PER_SELECTOR,
            "maxAnchors" to LookReading.MAX_ANCHORS,
            "maxFonts" to LookReading.MAX_FONTS,
        )

    /** Browser, version, system and mode, e.g. `chromium 141.0.7390.37; Mac OS X aarch64; headless`. Session thread only. */
    private fun renderer(userAgent: String): String {
        val browser = handles.browser
        val system = System.getProperty("os.name") + " " + System.getProperty("os.arch")
        val mode = if (HEADLESS_MARK in userAgent) "headless" else "headed"
        return "${browser.browserType().name()} ${browser.version()}; $system; $mode"
    }

    /** [address] without its query and fragment, which a page may change in place (`?slide=2`) and stay itself. */
    private fun withoutQuery(address: String): String = address.substringBefore('#').substringBefore('?')

    /** What settling one load of a look found. */
    private class LookSettled(
        val unsettled: List<String>,
        val pageHeight: Int,
        val viewport: Viewport,
        /** The page's address once it settled: a look whose page then goes to another address is no look of it. */
        val address: String,
    )

    /** One load of a look: its frame, the earlier frame when the page kept moving, and what was read with it. */
    private class LookLoad(
        val final: ByteArray,
        val moved: ByteArray?,
        val facts: LookFacts,
        val unsettled: List<String>,
        val viewport: Viewport,
        val renderer: String,
    ) {
        /**
         * This (second) load showed [first]'s page: it was read on the same path, and the site answered it with the same
         * status, when the browser reported both.
         */
        fun showsPageOf(first: LookLoad): Boolean =
            facts.path == first.facts.path && (facts.status == null || first.facts.status == null || facts.status == first.facts.status)

        /** [png] as a frame of [kind], with this load's areas fitted to its own size. */
        fun shot(
            png: ByteArray,
            kind: LookShotKind,
        ): LookShot {
            val size = PngSize.of(png) ?: throw BrowserActionException("a look's frame is not a PNG")
            return LookShot(png, kind, size.width, size.height, LookReading.areas(facts.areas, size.width, size.height))
        }
    }

    override suspend fun clearCookies() {
        perform("clear cookies") { handles.context.clearCookies() }
    }

    override suspend fun horizontalOverflow(
        width: Int,
        height: Int,
    ): Int? =
        perform("measure at ${width}x$height") {
            val own = page.viewportSize()
            page.setViewportSize(width, height)
            try {
                (page.evaluate(OVERFLOW_SCRIPT) as? Number)?.toInt()
            } finally {
                if (own != null) page.setViewportSize(own.width, own.height)
            }
        }

    override suspend fun resizeViewport(
        width: Int,
        height: Int,
    ): Viewport? =
        perform("show pages at ${width}x$height") {
            val own = page.viewportSize()
            page.setViewportSize(width, height)
            own?.let { Viewport(it.width, it.height) }
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
     * files such as `.env` into snapshots that reach the LLM (AGENTS.md rule 10), e.g. when a page talks an agent
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
            throw BrowserActionException("option \"$option\" not found; available options, those containing it first: $available")
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
        return captured to masked() + before + secretValues()
    }

    private fun secretValues(): List<String> = (page.evaluate(BundledScripts.secretValues) as? List<*>).orEmpty().filterIsInstance<String>()

    /** A text watch's start: the page's `performance.now()` then ([pageMillis]) and the harness time it matches. */
    private class WatchOrigin(
        val pageMillis: Double,
        val harness: HarnessTimestamp,
    ) {
        /** The harness time of a later page time of the same document (both clocks are monotonic). */
        fun harnessTime(pageTime: Double): HarnessTimestamp = harness + ((pageTime - pageMillis) * NANOS_PER_MILLI).toLong().nanoseconds
    }

    companion object {
        /** Joins a request in the target's own logs to Pətək's evidence (Faza 14). */
        const val CORRELATION_HEADER = "X-Petek-Correlation-Id"

        /** Attribute the snapshot writes on every numbered element. */
        const val REF_ATTRIBUTE = "data-petek-ref"

        /** How often a wait re-checks the page: the resolution of every measured latency (t1). */
        const val PROBE_POLLING_INTERVAL_MS = 50.0

        /** A text watch checks at most this often after DOM changes, so a busy page cannot keep it probing. */
        private const val WATCH_GAP_MS = 20.0

        /** A text watch nobody reads stops itself after this long (30 minutes) instead of probing forever. */
        private const val WATCH_MAX_MS = 1_800_000.0
        private const val NANOS_PER_MILLI = 1_000_000.0

        /** How long a look waits for the network to go idle before it settles the page; a page that polls never does. */
        private const val NETWORK_IDLE_WAIT_MS = 2_000.0

        /** The part of a look's settle budget web fonts may take, each time they are waited for. */
        private const val LOOK_FONTS_WAIT_MS = 3_000

        /** The network is quiet once no resource finished loading for this long. */
        private const val LOOK_QUIET_MS = 500

        /** The pause at each screen while a look scrolls through the page, after the next frame was drawn. */
        private const val LOOK_SCROLL_STEP_MS = 100

        /** Between a look's frames: a page that still moves after it shows another frame. */
        private val LOOK_FRAME_GAP = 500.milliseconds

        /** A look's second load and each of its frames. */
        private const val LOOK_LOAD_TIMEOUT_MS = 15_000.0

        /** Applied while a look's frame is taken only: no transition halfway, no caret. */
        private const val LOOK_STYLE = "*,*::before,*::after{transition:none!important;caret-color:transparent!important}"

        /** Chromium's user agent without a window. */
        private const val HEADLESS_MARK = "HeadlessChrome"

        /** What a look reports as not settled, in this order. */
        private val UNSETTLED = listOf("network", "fonts", "images")

        private const val LINKS_SCRIPT =
            "() => [...document.querySelectorAll('a[href]')].map(a => a.getAttribute('href'))" +
                ".filter(h => h && !/^(mailto|tel|javascript|data):/i.test(h) && !h.startsWith('#'))"
        private const val OVERFLOW_SCRIPT =
            "() => Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth)"

        private fun sameOrigin(
            address: URI,
            base: URI,
        ): Boolean {
            fun port(uri: URI) =
                if (uri.port >= 0) {
                    uri.port
                } else if (uri.scheme.equals("https", true)) {
                    443
                } else {
                    80
                }
            return address.scheme.equals(base.scheme, ignoreCase = true) &&
                address.host.equals(base.host, ignoreCase = true) &&
                port(address) == port(base)
        }

        private val URL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
        private val WEB_SCHEMES = setOf("http", "https")
        private const val HTTP_OK = 200
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
            val health = HealthRecorder(options.baseUrl)
            val credentials = PageCredentials(options.baseUrl, options.apiOrigin)
            val handles =
                try {
                    thread.runToCompletion {
                        PlaywrightHandles.create(options, connector, traffic, dialogs, mutations, clock, health, credentials)
                    }
                } catch (e: BrowserActionException) {
                    thread.close()
                    throw e
                } catch (e: Exception) {
                    thread.close()
                    val reason = if (e is PlaywrightException) PlaywrightFailures.reasonOf(e.message.orEmpty()) else e.message
                    throw BrowserActionException("could not open browser session '${options.label}': $reason", e)
                }
            val session =
                PlaywrightBrowserSession(options, thread, clock, handles, traffic, dialogs, mutations, health, credentials, onClosed)
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
