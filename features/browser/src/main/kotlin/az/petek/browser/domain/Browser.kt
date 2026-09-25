package az.petek.browser.domain

import az.petek.core.error.PetekException
import az.petek.core.time.HarnessTimestamp
import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration

/** An interactive element as the agent sees it. [ref] is stable only until the next snapshot. */
data class PageElement(
    val ref: Int,
    val role: String,
    val name: String,
    val tag: String,
    val testId: String?,
    val value: String?,
    val enabled: Boolean,
)

/** Compact, LLM-friendly view of the page: numbered interactive elements plus visible text (no raw HTML). */
data class PageSnapshot(
    val url: String,
    val title: String,
    val elements: List<PageElement>,
    val visibleText: String,
) {
    /**
     * Stable text form used in prompts, e.g.
     * `[3] button "Elan yarat" (testid=announcement-create)`; text truncated to [maxTextChars].
     */
    fun render(
        maxElements: Int = 150,
        maxTextChars: Int = 4000,
    ): String =
        buildString {
            appendLine("URL: $url")
            appendLine("Title: $title")
            appendLine("Elements:")
            elements.take(maxElements).forEach { e ->
                append("[${e.ref}] ${e.role} \"${e.name}\"")
                if (e.testId != null) append(" (testid=${e.testId})")
                if (!e.value.isNullOrEmpty()) append(" value=\"${e.value}\"")
                if (!e.enabled) append(" [disabled]")
                appendLine()
            }
            if (elements.size > maxElements) appendLine("… ${elements.size - maxElements} more elements omitted")
            appendLine("Visible text:")
            append(visibleText.take(maxTextChars))
        }
}

data class Viewport(
    val width: Int = 1280,
    val height: Int = 800,
)

data class SessionOptions(
    /** Owner label used for thread names and logs, e.g. `a07`. */
    val label: String,
    val baseUrl: URI,
    /** Restore cookies/storage saved after an earlier login. */
    val storageState: Path? = null,
    val viewport: Viewport = Viewport(),
    val defaultTimeout: Duration = Duration.parse("15s"),
)

/** Result of waiting for something to appear. [observedAt] is the harness time it was seen (t1). */
data class WaitOutcome(
    val found: Boolean,
    val observedAt: HarnessTimestamp?,
)

data class HttpProbeResult(
    val status: Int,
    val body: String,
)

enum class RealtimeTransport { WEBSOCKET, SSE, POLLING }

/** What the page used to receive live updates, detected from network traffic (the target does not have to say). */
data class NetworkObservation(
    val transports: Set<RealtimeTransport>,
    val details: List<String>,
)

/** The kinds of JavaScript dialog a page can open (`window.alert`, `confirm`, `prompt`, and the leave-page prompt). */
enum class DialogType {
    ALERT,
    CONFIRM,
    PROMPT,
    BEFOREUNLOAD,
    ;

    /** Lower-case name as the browser reports it, e.g. `confirm`. */
    val key: String get() = name.lowercase()
}

/**
 * A JavaScript dialog the page opened and the session accepted (OK / leave page; a prompt gets its default text).
 * Dialogs block the page until answered, so a session answers them at once and keeps them as evidence: [at] is the
 * harness time the dialog was seen, [message] its text (secrets typed by the session are masked).
 */
data class DialogEvent(
    val type: DialogType,
    val message: String,
    val at: HarnessTimestamp,
)

/**
 * One isolated browser context owned by one agent (own cookies, localStorage, sessionStorage).
 * Implementations confine every underlying browser call to the session's own thread (CLAUDE.md rule 9);
 * callers may invoke these suspend functions from any coroutine.
 */
interface BrowserSession {
    val label: String

    /** Absolute URL or a path relative to [SessionOptions.baseUrl]. */
    suspend fun navigate(pathOrUrl: String)

    suspend fun snapshot(): PageSnapshot

    // Agent (LLM) actions address elements by snapshot ref.
    suspend fun click(ref: Int)

    suspend fun fill(
        ref: Int,
        text: String,
        submit: Boolean = false,
    )

    suspend fun select(
        ref: Int,
        option: String,
    )

    // Deterministic `run` functions address elements by selector.
    suspend fun clickSelector(selector: String)

    suspend fun fillSelector(
        selector: String,
        text: String,
    )

    suspend fun selectSelector(
        selector: String,
        option: String,
    )

    suspend fun readText(selector: String): String?

    suspend fun readAttribute(
        selector: String,
        attribute: String,
    ): String?

    suspend fun waitForText(
        text: String,
        timeout: Duration,
    ): WaitOutcome

    suspend fun waitForSelector(
        selector: String,
        timeout: Duration,
    ): WaitOutcome

    suspend fun isTextVisible(text: String): Boolean

    suspend fun isSelectorVisible(selector: String): Boolean

    suspend fun count(selector: String): Int

    suspend fun currentUrl(): String

    suspend fun screenshot(): ByteArray

    /** Accessibility tree as YAML (Playwright ARIA snapshot). */
    suspend fun accessibilitySnapshot(): String

    suspend fun domSnapshot(): String

    suspend fun saveStorageState(path: Path)

    /** HTTP call that carries this session's cookies (for `http_status` assertions). */
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
    ): HttpProbeResult

    suspend fun networkObservation(): NetworkObservation

    /**
     * The dialogs accepted since the previous call, oldest first, and forgets them, so each dialog is reported once.
     * Callers drain after an action to show the model and the evidence what the page asked. Sessions that cannot
     * see dialogs keep the default: none.
     */
    suspend fun drainDialogs(): List<DialogEvent> = emptyList()

    suspend fun close()
}

fun interface BrowserSessionFactory {
    suspend fun open(options: SessionOptions): BrowserSession
}

enum class BrowserTopology {
    /**
     * Chromium browser servers shared by the agents; each agent connects with its own Playwright instance. One server
     * hosts up to [BrowserEngineConfig.contextsPerBrowser] sessions, and more servers start as more agents open
     * sessions, so any number of agents can run.
     */
    SHARED_SERVER,

    /** Fallback: one Chromium per agent. Heavier, but independent of the server protocol. */
    PER_SESSION,
}

/**
 * @property contextsPerBrowser how many sessions (browser contexts) one shared browser server hosts before the next
 *   server starts (SHARED_SERVER only). It shards large runs over several Chromium processes, so one browser never
 *   carries hundreds of contexts; it is not a limit on the number of sessions. Must be at least 1.
 */
data class BrowserEngineConfig(
    val headless: Boolean = true,
    val topology: BrowserTopology = BrowserTopology.SHARED_SERVER,
    val slowMo: Duration = Duration.ZERO,
    val contextsPerBrowser: Int = DEFAULT_CONTEXTS_PER_BROWSER,
) {
    init {
        require(contextsPerBrowser >= 1) { "contextsPerBrowser must be at least 1, was $contextsPerBrowser" }
    }

    companion object {
        /** Twenty contexts keep one Chromium responsive; 100 agents then use five browser servers. */
        const val DEFAULT_CONTEXTS_PER_BROWSER = 20
    }
}

/** Owns the browser process(es). [start] returns the factory agents use to open their sessions. */
interface BrowserEngine {
    suspend fun start(config: BrowserEngineConfig): BrowserSessionFactory

    suspend fun stop()
}

class BrowserActionException(
    message: String,
    cause: Throwable? = null,
) : PetekException(message, cause)
