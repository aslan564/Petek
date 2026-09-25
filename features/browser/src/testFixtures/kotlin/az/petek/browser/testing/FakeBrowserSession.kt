package az.petek.browser.testing

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.DialogEvent
import az.petek.browser.domain.DialogType
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.ObservedMutation
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.WaitOutcome
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * In-memory browser for unit tests. Tests set [snapshotProvider], [visibleTexts], [selectorTexts] and
 * [httpResponses]; every call is appended to [actions] as a readable string (e.g. `click 3`, `fillSelector #x=abc`).
 * Dialogs: [openDialog] queues one for [drainDialogs]; [onAction] lets a dialog appear as the effect of an action.
 * Requests: [observedMutations] is what [mutations] reads (filtered by time); [mutated] adds one at the clock's now.
 * Reading mutations is not an action and is not listed in [actions].
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

    override suspend fun isSelectorVisible(selector: String): Boolean = selector in visibleSelectors

    override suspend fun count(selector: String): Int = counts[selector] ?: 0

    override suspend fun currentUrl(): String = url

    override suspend fun screenshot(): ByteArray = "png:$label:$url".toByteArray()

    override suspend fun accessibilitySnapshot(): String = "- document \"$url\""

    override suspend fun domSnapshot(): String = "<html data-url=\"$url\"></html>"

    override suspend fun saveStorageState(path: Path) = record("saveStorageState $path")

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

    override suspend fun close() {
        closed = true
    }
}
