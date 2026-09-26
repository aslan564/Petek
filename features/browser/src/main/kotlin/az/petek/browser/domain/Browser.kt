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

package az.petek.browser.domain

import az.petek.core.error.PetekException
import az.petek.core.security.Secret
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

/**
 * The proxy one tester's browser goes out through (Faza 21, `PETEK_PROXIES`), so the site sees each tester from its own
 * IP address. [server] is `scheme://host:port`; the password is a [Secret] and never printed.
 */
data class BrowserProxy(
    val server: String,
    val username: String? = null,
    val password: Secret? = null,
) {
    override fun toString(): String = "BrowserProxy($server${username?.let { ", user=$it" }.orEmpty()})"
}

data class SessionOptions(
    /** Owner label used for thread names and logs, e.g. `a07`. */
    val label: String,
    val baseUrl: URI,
    /** Restore cookies/storage saved after an earlier login. */
    val storageState: Path? = null,
    val viewport: Viewport = Viewport(),
    val defaultTimeout: Duration = Duration.parse("15s"),
    /**
     * Seeded into the localStorage of [baseUrl]'s origin before any page script runs, on every page load (so a flag
     * such as "first-visit dialog dismissed" holds even after the site clears it). Other origins are left alone.
     */
    val localStorage: Map<String, String> = emptyMap(),
    /** This tester's own proxy (Faza 21); null goes out directly, like every other tester. */
    val proxy: BrowserProxy? = null,
    /**
     * Send `X-Petek-Correlation-Id` with every request of the session (Faza 14, `PETEK_CORRELATION_HEADER`), so the
     * target's own logs can be joined to the evidence. Off by default: a custom header makes cross-origin API calls
     * preflighted, which a site's CORS must allow.
     */
    val correlationHeader: Boolean = false,
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
 * A mutating request (`POST`, `PUT`, `PATCH`, `DELETE`) the session's page sent to the target's origin and the answer it
 * got: a form post as well as a `fetch`/XHR call. It is what a race verdict rests on (`only_one_succeeds`): code reads
 * which request the target accepted or refused instead of trusting the agent's summary (AGENTS.md rule 2).
 *
 * [path] is the URL path without query string or fragment (those may carry tokens), [status] the HTTP status of the
 * answer (a redirect after a form post counts with its own status, e.g. 303), and [at] the harness time the session saw
 * the answer (rule 1).
 */
data class ObservedMutation(
    val method: String,
    val path: String,
    val status: Int,
    val at: HarnessTimestamp,
) {
    /** `POST /tickets/t2/approve -> 303`, the form used in verdicts and reports. */
    fun describe(): String = "$method $path -> $status"

    companion object {
        /** The methods that change something on the target; only these are recorded. */
        val METHODS: Set<String> = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}

/**
 * What went wrong on the pages a session loaded, as the browser itself saw it (Faza 13 blind patterns: console and
 * network errors, slow endpoints). Only the target's origin counts for requests; texts are redacted like the rest of
 * the session's output.
 */
data class PageHealth(
    /** `console.error` messages and uncaught page exceptions, oldest first. */
    val consoleErrors: List<String>,
    /** Requests to the target that failed: `GET /api/x -> 500`, or `GET /x -> net::ERR_…` when no answer came. */
    val failedRequests: List<String>,
    /** Requests to the target that took at least the asked threshold. */
    val slowResponses: List<SlowResponse>,
) {
    val isHealthy: Boolean get() = consoleErrors.isEmpty() && failedRequests.isEmpty() && slowResponses.isEmpty()

    companion object {
        val NONE: PageHealth = PageHealth(emptyList(), emptyList(), emptyList())
    }
}

/** One slow request to the target: `GET /api/reports` answered in [millis] ms (browser-measured, request to end). */
data class SlowResponse(
    val method: String,
    val path: String,
    val millis: Long,
) {
    fun describe(): String = "$method $path took $millis ms"
}

/**
 * One isolated browser context owned by one agent (own cookies, localStorage, sessionStorage).
 * Implementations confine every underlying browser call to the session's own thread (AGENTS.md rule 9);
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

    /**
     * Tags the session's next requests with [id] as `X-Petek-Correlation-Id` (null stops it) when the session was opened
     * with [SessionOptions.correlationHeader]; otherwise nothing happens.
     */
    suspend fun setCorrelationId(id: String?) = Unit

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

    /**
     * The mutating requests the page sent to the target's origin (scheme, host and port of [SessionOptions.baseUrl])
     * whose answers the session saw at or after [since], oldest first. Unlike [drainDialogs] nothing is forgotten, so
     * several readers may ask for overlapping windows. Requests to other origins (analytics, CDNs) and requests made
     * by [request] (assertion probes, not the page) are not included.
     *
     * An answer is timestamped when the session sees it, which may be a moment after it arrived; reading makes the
     * session catch up first. A caller that wants only the requests of one action therefore reads once right before
     * taking the action's start time (so answers to earlier requests are seen, and timestamped, before it) and once
     * after the action. Sessions that cannot see network traffic keep the default: none.
     */
    suspend fun mutations(since: HarnessTimestamp): List<ObservedMutation> = emptyList()

    /**
     * Console errors, failed requests to the target and requests that took at least [slowAfter], seen at or after
     * [since]. Sessions that cannot see them keep the default: nothing seen.
     */
    suspend fun health(
        since: HarnessTimestamp,
        slowAfter: Duration,
    ): PageHealth = PageHealth.NONE

    /** Paths of the links on the current page that stay on the target's origin (no `mailto:`, no other hosts). */
    suspend fun links(): List<String> = emptyList()

    /** The browser's back button; false when there was no page to go back to (or the session cannot). */
    suspend fun goBack(): Boolean = false

    /** Forgets the session's cookies, as an expired session would; sessions that cannot keep the default no-op. */
    suspend fun clearCookies() = Unit

    /**
     * Shows the current page at [width] × [height] (a phone), measures how many pixels it is wider than the screen
     * and restores the session's own viewport; null when the session cannot measure.
     */
    suspend fun horizontalOverflow(
        width: Int,
        height: Int,
    ): Int? = null

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
    /**
     * Accept TLS certificates the browser does not trust (a staging site with a self-signed certificate, a corporate
     * proxy that re-signs traffic). Off by default: a target with a broken certificate is a finding, not noise, unless
     * the owner says otherwise.
     */
    val ignoreTlsErrors: Boolean = false,
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

open class BrowserActionException(
    message: String,
    cause: Throwable? = null,
) : PetekException(message, cause)

/**
 * The browser context itself is gone (the page or browser crashed, or was closed under the session): no call on this
 * session can succeed any more. The runner answers it by restoring the tester in a new context with the same identity
 * and its saved `storage_state` (docs/PLAN.md Faza 3).
 */
class BrowserContextLostException(
    message: String,
    cause: Throwable? = null,
) : BrowserActionException(message, cause)
