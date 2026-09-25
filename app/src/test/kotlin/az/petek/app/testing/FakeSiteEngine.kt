/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.testing

import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.SessionOptions
import az.petek.browser.testing.FakeBrowserSession
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small scripted site for the explorer in app tests (no Chromium may start there): [page] defines what a path
 * shows, as the numbered elements of a snapshot and a DOM carrying the same `data-petek-ref`s, the way the real browser
 * adapter produces them. Sessions record every call in their [FakeBrowserSession]; a session opened with a storage
 * state (a logged-in role) sees the pages defined for its [SessionOptions.label] first.
 */
class FakeSiteEngine(
    val base: URI,
) : BrowserEngine {
    class Page(
        val title: String,
        val elements: List<PageElement>,
        val html: String,
    )

    private val pages = ConcurrentHashMap<Pair<String, String?>, Page>()
    val sessions = CopyOnWriteArrayList<Session>()
    private val starts = AtomicInteger()
    private val stops = AtomicInteger()

    val startCount: Int get() = starts.get()
    val stopCount: Int get() = stops.get()

    /** Defines [path] as seen by sessions labelled [view] (null: everyone). */
    fun page(
        path: String,
        title: String,
        view: String? = null,
        build: PageBuilder.() -> Unit = {},
    ) {
        val builder = PageBuilder().apply(build)
        pages[path to view] = Page(title, builder.elements.toList(), builder.html(title))
    }

    fun remove(path: String) {
        pages.keys.filter { it.first == path }.forEach { pages.remove(it) }
    }

    override suspend fun start(config: BrowserEngineConfig): BrowserSessionFactory {
        starts.incrementAndGet()
        return BrowserSessionFactory { options -> Session(options.label).also { sessions += it } }
    }

    override suspend fun stop() {
        stops.incrementAndGet()
    }

    inner class Session(
        val view: String,
        val recorder: FakeBrowserSession = FakeBrowserSession(view),
    ) : BrowserSession by recorder {
        @Volatile
        private var current: URI = URI("about:blank")

        private fun lookup(path: String): Page? = pages[path to view] ?: pages[path to null]

        private fun pathOf(uri: URI): String = uri.rawPath.orEmpty().ifEmpty { "/" }

        override suspend fun navigate(pathOrUrl: String) {
            recorder.navigate(pathOrUrl)
            current = base.resolve(pathOrUrl)
        }

        override suspend fun snapshot(): PageSnapshot {
            val page = lookup(pathOf(current))
            return PageSnapshot(
                current.toString(),
                page?.title ?: "Tapılmadı",
                page?.elements.orEmpty(),
                page?.title ?: "Səhifə tapılmadı.",
            )
        }

        override suspend fun domSnapshot(): String = lookup(pathOf(current))?.html ?: "<html><body><p>Səhifə tapılmadı.</p></body></html>"

        override suspend fun currentUrl(): String = current.toString()

        override suspend fun screenshot(): ByteArray = "png:$view:$current".toByteArray()

        override suspend fun request(
            method: String,
            path: String,
            body: String?,
        ): HttpProbeResult {
            recorder.request(method, path, body)
            val page = lookup(pathOf(base.resolve(path))) ?: return HttpProbeResult(404, "Səhifə tapılmadı.")
            return HttpProbeResult(200, page.html)
        }
    }

    /** Builds one page's elements and its matching DOM. */
    class PageBuilder {
        val elements = mutableListOf<PageElement>()
        private val body = StringBuilder()

        private fun ref(
            role: String,
            name: String,
            tag: String,
            testId: String?,
        ): Int {
            val ref = elements.size + 1
            elements += PageElement(ref, role, name, tag, testId, null, enabled = true)
            return ref
        }

        private fun attrs(
            ref: Int,
            testId: String?,
        ): String = " data-petek-ref=\"$ref\"" + (testId?.let { " data-testid=\"$it\"" } ?: "")

        fun link(
            text: String,
            href: String,
        ) {
            val ref = ref("link", text, "a", null)
            body.append("<a href=\"$href\"${attrs(ref, null)}>$text</a>")
        }

        fun form(
            action: String,
            build: FormBuilder.() -> Unit,
        ) {
            body.append("<form action=\"$action\" method=\"post\">")
            FormBuilder().build()
            body.append("</form>")
        }

        inner class FormBuilder {
            fun field(
                label: String,
                name: String,
                type: String = "text",
                testId: String? = null,
            ) {
                val id = testId ?: "f-$name"
                val ref = ref("textbox", label, "input", testId)
                body.append(
                    "<label for=\"$id\">$label</label><input type=\"$type\" id=\"$id\" name=\"$name\"${attrs(ref, testId)} required>",
                )
            }

            fun submit(
                text: String,
                testId: String,
            ) {
                val ref = ref("button", text, "button", testId)
                body.append("<button type=\"submit\"${attrs(ref, testId)}>$text</button>")
            }
        }

        fun html(title: String): String = "<!DOCTYPE html><html><head><title>$title</title></head><body>$body</body></html>"
    }
}
