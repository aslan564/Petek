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

package az.petek.browser.testing

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.DialogType
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.ObservedMutation
import az.petek.browser.domain.PageFacts
import az.petek.browser.domain.PageHealth
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.PageTiming
import az.petek.browser.domain.TextWatch
import az.petek.browser.domain.Viewport
import az.petek.browser.domain.WaitOutcome
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * In-memory browser for unit tests. Tests set [snapshotProvider], [visibleTexts], [selectorTexts] and
 * [httpResponses]; every call is appended to [actions] as a readable string (e.g. `click 3`, `fillSelector #x=abc`).
 * Dialogs: [openDialog] queues one for [drainDialogs]; [onAction] lets a dialog appear as the effect of an action.
 * Requests: [observedMutations] is what [mutations] reads (filtered by time); [mutated] adds one at the clock's now.
 * Reading mutations is not an action and is not listed in [actions].
 * Text watches: [showText] makes a text visible at the clock's now; a watch started before sees it at that time, one
 * started while the text is visible already answers [TextWatch.WasThere]; [navigate] ends every watch like a new page
 * does; [canWatch] false plays a session that cannot watch. Watching is not an action either; [watched] lists the watches.
 */
class FakeBrowserSession(
    override val label: String = "fake",
    private val clock: HarnessClock? = null,
) : BrowserSession {
    val actions = CopyOnWriteArrayList<String>()
    var url: String = "about:blank"
    var snapshotProvider: () -> PageSnapshot = { PageSnapshot(url, "", emptyList(), "") }
    val visibleTexts: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()
    val visibleSelectors: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()
    val selectorTexts = java.util.concurrent.ConcurrentHashMap<String, String>()
    val attributes = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, String>()
    val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val httpResponses = java.util.concurrent.ConcurrentHashMap<String, HttpProbeResult>()
    var observation = NetworkObservation(emptySet(), emptyList())
    var failOn: ((String) -> Boolean) = { false }
    var closed = false
    private val pendingDialogs = ArrayList<DialogEvent>()

    /** The mutating requests "the page sent"; tests add to it directly or through [mutated]. */
    val observedMutations = CopyOnWriteArrayList<ObservedMutation>()

    private fun record(action: String) {
        if (failOn(action)) throw BrowserActionException("scripted failure: $action")
        actions += action
        onAction(action)
    }

    override suspend fun navigate(pathOrUrl: String) {
        record("navigate $pathOrUrl")
        url = pathOrUrl
        // A new page: the watches of the old one are gone with it.
        watches.clear()
    }

    override suspend fun snapshot(): PageSnapshot = snapshotProvider()

    override suspend fun click(ref: Int) = record("click $ref")

    override suspend fun fill(
        ref: Int,
        text: String,
        submit: Boolean,
    ) = record("fill $ref=$text" + if (submit) " +submit" else "")

    override suspend fun select(
        ref: Int,
        option: String,
    ) = record("select $ref=$option")

    override suspend fun clickSelector(selector: String) = record("clickSelector $selector")

    override suspend fun fillSelector(
        selector: String,
        text: String,
    ) = record("fillSelector $selector=$text")

    override suspend fun selectSelector(
        selector: String,
        option: String,
    ) = record("selectSelector $selector=$option")

    override suspend fun readText(selector: String): String? = selectorTexts[selector]

    override suspend fun readAttribute(
        selector: String,
        attribute: String,
    ): String? = attributes[selector to attribute]

    override suspend fun waitForText(
        text: String,
        timeout: Duration,
    ): WaitOutcome = WaitOutcome(text in visibleTexts, if (text in visibleTexts) clock?.now() else null)

    override suspend fun waitForSelector(
        selector: String,
        timeout: Duration,
    ): WaitOutcome = WaitOutcome(selector in visibleSelectors, if (selector in visibleSelectors) clock?.now() else null)

    override suspend fun isTextVisible(text: String): Boolean = text in visibleTexts

    /** When each text became visible through [showText]. */
    val shownAt = ConcurrentHashMap<String, HarnessTimestamp>()

    /** Every watch started, as `key` to `text`, in order. */
    val watched = CopyOnWriteArrayList<Pair<String, String>>()

    /** False plays a session that cannot watch the page: every watch is [TextWatch.Lost]. */
    @Volatile
    var canWatch: Boolean = true

    private class Watch(
        val text: String,
        val armedAt: HarnessTimestamp,
        val before: Boolean,
    )

    private val watches = ConcurrentHashMap<String, Watch>()

    /** The keys of the watches running now: started, not stopped, the page not left. */
    val activeWatches: Set<String> get() = watches.keys.toSet()

    /** The page shows [text] from now on (the clock's now), as a live update would. */
    fun showText(text: String) {
        shownAt[text] = now()
        visibleTexts += text
    }

    override suspend fun watchText(
        key: String,
        text: String,
    ): TextWatch {
        if (!canWatch) return TextWatch.Lost
        watched += key to text
        val before = text in visibleTexts
        watches[key] = Watch(text, now(), before)
        return if (before) TextWatch.WasThere else TextWatch.NotYet
    }

    override suspend fun stopTextWatch(key: String): TextWatch {
        val watch = watches.remove(key) ?: return TextWatch.Lost
        return when {
            watch.before -> TextWatch.WasThere
            watch.text !in visibleTexts -> TextWatch.NotYet
            else -> TextWatch.Seen(shownAt[watch.text]?.takeIf { it.monotonicNanos >= watch.armedAt.monotonicNanos } ?: now())
        }
    }

    private fun now(): HarnessTimestamp = clock?.now() ?: HarnessTimestamp(Instant.EPOCH, 0)

    override suspend fun isSelectorVisible(selector: String): Boolean = selector in visibleSelectors

    override suspend fun count(selector: String): Int = counts[selector] ?: 0

    override suspend fun currentUrl(): String = url

    override suspend fun screenshot(): ByteArray = "png:$label:$url".toByteArray()

    override suspend fun accessibilitySnapshot(): String = "- document \"$url\""

    override suspend fun domSnapshot(): String = "<html data-url=\"$url\"></html>"

    override suspend fun saveStorageState(path: Path) = record("saveStorageState $path")

    /** Correlation ids the harness tagged the session's requests with, in order (kept apart from [actions]). */
    val correlationIds = CopyOnWriteArrayList<String?>()

    override suspend fun setCorrelationId(id: String?) {
        correlationIds += id
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): HttpProbeResult {
        record("request $method $path")
        return httpResponses["$method $path"] ?: HttpProbeResult(404, "")
    }

    override suspend fun networkObservation(): NetworkObservation = observation

    /** Simulates the page opening a dialog that the session accepted; reported once by [drainDialogs]. */
    fun openDialog(
        type: DialogType,
        message: String,
    ) {
        val at = clock?.now() ?: HarnessTimestamp(Instant.EPOCH, 0)
        synchronized(pendingDialogs) { pendingDialogs += DialogEvent(type, message, at) }
    }

    /** Called before a recorded action runs (e.g. `click 3`) so a test can open a dialog "caused" by that action. */
    var onAction: (String) -> Unit = {}

    override suspend fun drainDialogs(): List<DialogEvent> =
        synchronized(pendingDialogs) {
            val drained = pendingDialogs.toList()
            pendingDialogs.clear()
            drained
        }

    /** Simulates the page sending a mutating request that the target answered with [status], seen now. */
    fun mutated(
        method: String,
        path: String,
        status: Int,
    ): ObservedMutation {
        val at = clock?.now() ?: HarnessTimestamp(Instant.EPOCH, 0)
        return ObservedMutation(method.uppercase(), path, status, at).also { observedMutations += it }
    }

    /** When set, [mutations] throws it, as a broken session would. */
    @Volatile
    var mutationsFailure: Exception? = null

    override suspend fun mutations(since: HarnessTimestamp): List<ObservedMutation> {
        mutationsFailure?.let { throw it }
        return observedMutations.filter { it.at.monotonicNanos >= since.monotonicNanos }
    }

    /** What [health] reports, whatever the window; tests set it to play a broken page. */
    @Volatile
    var pageHealth: PageHealth = PageHealth.NONE

    /** The links of the page, per URL; [links] answers those of the current [url]. */
    val pageLinks = ConcurrentHashMap<String, List<String>>()

    /** Overflow reported by [horizontalOverflow]; null plays a session that cannot measure. */
    @Volatile
    var overflow: Int? = 0

    override suspend fun health(
        since: HarnessTimestamp,
        slowAfter: Duration,
    ): PageHealth = pageHealth.copy(slowResponses = pageHealth.slowResponses.filter { it.millis >= slowAfter.inWholeMilliseconds })

    override suspend fun links(): List<String> = pageLinks[url].orEmpty()

    override suspend fun goBack(): Boolean {
        record("back")
        val previous = actions.filter { it.startsWith("navigate ") }.dropLast(1).lastOrNull() ?: return false
        url = previous.removePrefix("navigate ")
        return true
    }

    override suspend fun clearCookies() = record("clearCookies")

    /** The viewport [resizeViewport] set last; starts as a desktop browser's. */
    @Volatile
    var viewport: Viewport = Viewport(1280, 720)

    override suspend fun resizeViewport(
        width: Int,
        height: Int,
    ): Viewport {
        record("resize ${width}x$height")
        val before = viewport
        viewport = Viewport(width, height)
        return before
    }

    override suspend fun horizontalOverflow(
        width: Int,
        height: Int,
    ): Int? {
        record("viewport ${width}x$height")
        return overflow
    }

    /** Facts of the page at each address; [facts] for any other. */
    val pageFactsByUrl = ConcurrentHashMap<String, PageFacts>()
    var facts: PageFacts? = null

    override suspend fun pageFacts(): PageFacts? = pageFactsByUrl[url] ?: facts

    /** What [pageTiming] reports for the current page, by URL, else [timing]; null plays a session that cannot read it. */
    val pageTimingByUrl = ConcurrentHashMap<String, PageTiming>()
    var timing: PageTiming? = null

    override suspend fun pageTiming(): PageTiming? = pageTimingByUrl[url] ?: timing

    override suspend fun close() {
        closed = true
    }
}
