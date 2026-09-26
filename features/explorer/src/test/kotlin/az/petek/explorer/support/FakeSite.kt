/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.support

import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.RealtimeTransport
import az.petek.browser.domain.WaitOutcome
import az.petek.browser.testing.FakeBrowserSession
import az.petek.core.testing.FakeHarnessClock
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A scripted web site for explorer tests. Pages are defined per path (and optionally per viewpoint); each page's
 * element list and DOM are built together, so the DOM carries the same `data-petek-ref`s as the snapshot, exactly as
 * the real browser adapter produces them. Sessions record every call (via [FakeBrowserSession.actions]) and advance
 * the fake clock on each navigation by the page's load time.
 */
class FakeSite(
    val base: URI = URI("https://site.test"),
    val clock: FakeHarnessClock = FakeHarnessClock(),
) {
    class Page(
        val title: String,
        val elements: List<PageElement>,
        val html: String,
        val text: String,
        val loadTime: Duration,
        val transports: Set<RealtimeTransport>,
    )

    private val pages = HashMap<Pair<String, String?>, Page>()
    private val redirects = HashMap<Pair<String, String?>, String>()
    private val statuses = HashMap<Pair<String, String?>, Int>()
    private val laterRedirects = HashMap<String, String>()
    val sessions = CopyOnWriteArrayList<Session>()

    /** Selector of a submit button -> what submitting it creates: the page path it lands on, and whether others see it live. */
    val creates = HashMap<String, Pair<String, Boolean>>()

    /** Served on `GET /robots.txt` when set. */
    var robotsTxt: String? = null

    /** Called on every navigation (after the clock advanced), e.g. to cancel the exploring coroutine. */
    var onNavigate: (String) -> Unit = {}

    fun page(
        path: String,
        title: String,
        view: String? = null,
        loadTime: Duration = 100.milliseconds,
        transports: Set<RealtimeTransport> = emptySet(),
        build: PageBuilder.() -> Unit = {},
    ) {
        val builder = PageBuilder().apply(build)
        pages[path to view] = Page(title, builder.elements, builder.html(title), builder.visibleText(title), loadTime, transports)
    }

    fun redirect(
        from: String,
        to: String,
        view: String? = null,
    ) {
        redirects[from to view] = to
    }

    fun status(
        path: String,
        status: Int,
        view: String? = null,
    ) {
        statuses[path to view] = status
    }

    /**
     * The page at [from] moves the browser to [to] only after it loaded (a delayed meta refresh or a script): the
     * address read right after the navigation is still [from], the first snapshot already shows [to].
     */
    fun laterRedirect(
        from: String,
        to: String,
    ) {
        laterRedirects[from] = to
    }

    /**
     * The page at [path] renders like a single-page application: its first [emptySnapshots] snapshots after every
     * navigation show no title, no elements and no text, only later ones show the page.
     */
    fun rendersLate(
        path: String,
        emptySnapshots: Int,
    ) {
        lateRenders[path] = emptySnapshots
    }

    private val lateRenders = HashMap<String, Int>()
    private val pendingEmptySnapshots = HashMap<String, Int>()

    fun session(view: String = "anonymous"): Session = Session(view).also { sessions += it }

    /** A factory whose sessions view the site anonymously. */
    fun factory(): BrowserSessionFactory = BrowserSessionFactory { session() }

    private fun <T> lookup(
        map: Map<Pair<String, String?>, T>,
        path: String,
        view: String,
    ): T? = map[path to view] ?: map[path to null]

    inner class Session(
        val view: String,
        val recorder: FakeBrowserSession = FakeBrowserSession(view, clock),
    ) : BrowserSession by recorder {
        var current: URI = URI("about:blank")
        private val typed = LinkedHashMap<String, String>()
        val liveTexts: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet()
        val requests = CopyOnWriteArrayList<String>()
        val navigations = CopyOnWriteArrayList<String>()

        val actions: List<String> get() = recorder.actions

        private fun resolve(pathOrUrl: String): URI = base.resolve(pathOrUrl)

        override suspend fun navigate(pathOrUrl: String) {
            recorder.navigate(pathOrUrl)
            navigations += pathOrUrl
            var target = resolve(pathOrUrl)
            var hops = 0
            while (hops++ < MAX_REDIRECTS) {
                val next = lookup(redirects, target.rawPath.orEmpty().ifEmpty { "/" }, view) ?: break
                target = resolve(next)
            }
            current = target
            clock.advance(lookup(pages, pathOf(target), view)?.loadTime ?: 50.milliseconds)
            lateRenders[pathOf(target)]?.let { pendingEmptySnapshots[pathOf(target)] = it }
            onNavigate(pathOrUrl)
        }

        private fun pathOf(uri: URI): String = uri.rawPath.orEmpty().ifEmpty { "/" }

        private fun currentPage(): Page? = lookup(pages, pathOf(current), view)

        override suspend fun snapshot(): PageSnapshot {
            laterRedirects[pathOf(current)]?.takeIf { current.host == base.host }?.let { current = resolve(it) }
            val pending = pendingEmptySnapshots[pathOf(current)] ?: 0
            if (pending > 0) {
                pendingEmptySnapshots[pathOf(current)] = pending - 1
                recorder.actions += "empty snapshot"
                return PageSnapshot(current.toString(), "", emptyList(), "")
            }
            val page = currentPage()
            val text = (listOf(page?.text ?: "Səhifə tapılmadı.") + liveTexts).joinToString("\n")
            return PageSnapshot(current.toString(), page?.title ?: "Not found", page?.elements.orEmpty(), text)
        }

        override suspend fun domSnapshot(): String = currentPage()?.html ?: "<html><body><p>Səhifə tapılmadı.</p></body></html>"

        override suspend fun currentUrl(): String = current.toString()

        override suspend fun request(
            method: String,
            path: String,
            body: String?,
        ): HttpProbeResult {
            recorder.actions += "request $method $path"
            requests += "$method $path"
            val uri = resolve(path)
            val key = pathOf(uri)
            if (key == "/robots.txt") robotsTxt?.let { return HttpProbeResult(200, it) }
            lookup(statuses, key, view)?.let { return HttpProbeResult(it, "status $it for $key") }
            if (lookup(redirects, key, view) != null) return HttpProbeResult(303, "")
            val page = lookup(pages, key, view) ?: return HttpProbeResult(404, "Səhifə tapılmadı.")
            return HttpProbeResult(200, page.html)
        }

        override suspend fun networkObservation(): NetworkObservation {
            val transports = currentPage()?.transports.orEmpty()
            return NetworkObservation(transports, transports.map { "${it.label()} ${base.resolve("/events")}" })
        }

        override suspend fun fillSelector(
            selector: String,
            text: String,
        ) {
            recorder.fillSelector(selector, text)
            typed[selector] = text
        }

        override suspend fun selectSelector(
            selector: String,
            option: String,
        ) {
            recorder.selectSelector(selector, option)
            typed[selector] = option
        }

        override suspend fun clickSelector(selector: String) {
            recorder.clickSelector(selector)
            val (landing, live) = creates[selector] ?: return
            val created = typed.values.firstOrNull().orEmpty()
            current = resolve(landing)
            liveTexts += created
            if (live) sessions.filter { it !== this }.forEach { it.liveTexts += created }
            typed.clear()
        }

        override suspend fun waitForText(
            text: String,
            timeout: Duration,
        ): WaitOutcome {
            val shown = snapshot().visibleText.contains(text, ignoreCase = true)
            return WaitOutcome(shown, if (shown) clock.now() else null)
        }

        override suspend fun isSelectorVisible(selector: String): Boolean = true

        override suspend fun screenshot(): ByteArray = "png:$view:$current".toByteArray()
    }

    private fun RealtimeTransport.label(): String =
        when (this) {
            RealtimeTransport.WEBSOCKET -> "WebSocket"
            RealtimeTransport.SSE -> "SSE"
            RealtimeTransport.POLLING -> "Polling GET"
        }

    /** Builds one page's element list and matching DOM. */
    class PageBuilder {
        val elements = mutableListOf<PageElement>()
        private val body = StringBuilder()
        private val lines = mutableListOf<String>()

        private fun ref(
            role: String,
            name: String,
            tag: String,
            testId: String?,
            value: String? = null,
        ): Int {
            val ref = elements.size + 1
            elements += PageElement(ref, role, name, tag, testId, value, enabled = true)
            return ref
        }

        private fun attrs(
            ref: Int?,
            testId: String?,
        ): String = (ref?.let { " data-petek-ref=\"$it\"" } ?: "") + (testId?.let { " data-testid=\"$it\"" } ?: "")

        fun text(line: String) {
            lines += line
            body.append("<p>").append(escape(line)).append("</p>")
        }

        fun marker(testId: String) {
            body.append("<div data-testid=\"$testId\"></div>")
        }

        fun link(
            text: String,
            href: String,
            testId: String? = null,
        ) {
            val ref = ref("link", text, "a", testId)
            lines += text
            body.append("<a href=\"${escape(href)}\"${attrs(ref, testId)}>${escape(text)}</a>")
        }

        fun button(
            text: String,
            testId: String? = null,
        ) {
            val ref = ref("button", text, "button", testId)
            lines += text
            body.append("<button type=\"button\"${attrs(ref, testId)}>${escape(text)}</button>")
        }

        fun form(
            action: String,
            method: String = "post",
            build: FormBuilder.() -> Unit,
        ) {
            body.append("<form action=\"${escape(action)}\" method=\"$method\">")
            FormBuilder().build()
            body.append("</form>")
        }

        inner class FormBuilder {
            fun field(
                label: String,
                name: String,
                type: String = "text",
                testId: String? = null,
                required: Boolean = false,
                value: String = "",
            ) {
                val id = testId ?: "f-$name"
                val role = if (type == "checkbox") "checkbox" else "textbox"
                val shown = if (type == "password" && value.isNotEmpty()) "******" else value
                val ref = ref(role, label, "input", testId, shown)
                lines += label
                body.append("<label for=\"$id\">${escape(label)}</label>")
                body.append(
                    "<input type=\"$type\" id=\"$id\" name=\"$name\"${attrs(ref, testId)}" +
                        (if (required) " required" else "") + (if (value.isNotEmpty()) " value=\"${escape(value)}\"" else "") + ">",
                )
            }

            fun hidden(
                name: String,
                value: String,
            ) {
                body.append("<input type=\"hidden\" name=\"$name\" value=\"${escape(value)}\">")
            }

            fun textarea(
                label: String,
                name: String,
                testId: String? = null,
            ) {
                val id = testId ?: "f-$name"
                val ref = ref("textbox", label, "textarea", testId, "")
                lines += label
                body.append(
                    "<label for=\"$id\">${escape(label)}</label><textarea id=\"$id\" name=\"$name\"${attrs(ref, testId)}></textarea>",
                )
            }

            fun select(
                label: String,
                name: String,
                options: List<String>,
                testId: String? = null,
            ) {
                val id = testId ?: "f-$name"
                val ref = ref("combobox", label, "select", testId, "")
                lines += label
                body.append("<label for=\"$id\">${escape(label)}</label><select id=\"$id\" name=\"$name\"${attrs(ref, testId)}>")
                body.append("<option value=\"\">Seçin</option>")
                options.forEach { body.append("<option value=\"${escape(it)}\">${escape(it)}</option>") }
                body.append("</select>")
            }

            fun submit(
                text: String,
                testId: String? = null,
                formAction: String? = null,
                formMethod: String? = null,
            ) {
                val ref = ref("button", text, "button", testId)
                lines += text
                val overrides =
                    (formAction?.let { " formaction=\"${escape(it)}\"" } ?: "") + (formMethod?.let { " formmethod=\"$it\"" } ?: "")
                body.append("<button type=\"submit\"${attrs(ref, testId)}$overrides>${escape(text)}</button>")
            }
        }

        fun html(title: String): String = "<!DOCTYPE html><html><head><title>${escape(title)}</title></head><body>$body</body></html>"

        fun visibleText(title: String): String = (listOf(title) + lines).joinToString("\n")

        private fun escape(text: String): String =
            text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
