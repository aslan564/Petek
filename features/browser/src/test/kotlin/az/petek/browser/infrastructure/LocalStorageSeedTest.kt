/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.core.time.SystemHarnessClock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI

/** [SessionOptions.localStorage] reaches the target origin before the page's own scripts run, and nowhere else. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalStorageSeedTest {
    private val server =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing { get("/storage") { call.respondText(STORAGE_PAGE, ContentType.Text.Html) } }
        }.start(wait = false)
    private val port =
        runBlocking {
            server.engine
                .resolvedConnectors()
                .first()
                .port
        }
    private val baseUrl = URI("http://127.0.0.1:$port")
    private val engine = PlaywrightBrowserEngine(SystemHarnessClock())
    private lateinit var sessions: BrowserSessionFactory

    @BeforeAll
    fun startEngine() =
        runBlocking<Unit> {
            sessions = engine.start(BrowserEngineConfig())
        }

    @AfterAll
    fun stopEngine() {
        runBlocking { engine.stop() }
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private fun withSession(
        localStorage: Map<String, String>,
        block: suspend (BrowserSession) -> Unit,
    ) = runBlocking<Unit> {
        val session = sessions.open(SessionOptions("seed", baseUrl, localStorage = localStorage))
        try {
            block(session)
        } finally {
            session.close()
        }
    }

    @Test
    fun `the page's first script already reads the seeded values`() =
        withSession(mapOf("kadro:lang" to "az", "kadro:domain_dialog_dismissed" to "1")) { session ->
            session.navigate("/storage")

            session.readText("#seen") shouldBe "kadro:lang=az; kadro:domain_dialog_dismissed=1"
        }

    @Test
    fun `values the page changed are seeded again on the next page load`() =
        withSession(mapOf("kadro:lang" to "az")) { session ->
            session.navigate("/storage")
            session.navigate("/storage")

            session.readText("#seen")!! shouldContain "kadro:lang=az"
        }

    @Test
    fun `another origin does not receive the target's values`() =
        withSession(mapOf("kadro:lang" to "az")) { session ->
            session.navigate("http://localhost:$port/storage")

            session.readText("#seen") shouldBe "kadro:lang=null; kadro:domain_dialog_dismissed=null"
        }

    @Test
    fun `values with quotes, backslashes and line breaks arrive unchanged`() =
        withSession(mapOf("kadro:lang" to "a\"b\\c\nd")) { session ->
            session.navigate("/storage")

            session.readText("#raw") shouldBe "\"a\\\"b\\\\c\\nd\""
        }

    @Test
    fun `without values nothing is seeded`() =
        withSession(emptyMap()) { session ->
            session.navigate("/storage")

            session.readText("#seen") shouldBe "kadro:lang=null; kadro:domain_dialog_dismissed=null"
        }

    @Test
    fun `the script is only built when there is something to seed and names the target origin`() {
        LocalStorageSeed.script(SessionOptions("a", baseUrl)).shouldBeNull()
        val script = LocalStorageSeed.script(SessionOptions("a", URI("https://KadroHR.com:443/app"), localStorage = mapOf("k" to "v")))!!
        script shouldContain "location.origin !== \"https://kadrohr.com\""
        script shouldContain "[\"k\", \"v\"]"
        script shouldNotContain "443"
    }

    @Test
    fun `origins keep non-default ports and drop default ones`() {
        LocalStorageSeed.originOf(URI("http://127.0.0.1:8080/x")) shouldBe "http://127.0.0.1:8080"
        LocalStorageSeed.originOf(URI("http://example.test:80")) shouldBe "http://example.test"
        LocalStorageSeed.originOf(URI("HTTPS://Example.Test")) shouldBe "https://example.test"
    }

    @Test
    fun `strings are escaped as JavaScript literals`() {
        LocalStorageSeed.jsString("a\"b\\c\n\u0001\u2028</script>") shouldBe "\"a\\\"b\\\\c\\u000a\\u0001\\u2028</script>\""
    }

    private companion object {
        /** Shows what localStorage held when the page's first script ran, then changes it the way a site would. */
        val STORAGE_PAGE =
            """
            <!doctype html>
            <html><head><title>Storage</title></head><body>
            <p id="seen"></p><p id="raw"></p>
            <script>
              const keys = ['kadro:lang', 'kadro:domain_dialog_dismissed'];
              document.getElementById('seen').textContent = keys.map(k => k + '=' + localStorage.getItem(k)).join('; ');
              document.getElementById('raw').textContent = JSON.stringify(localStorage.getItem('kadro:lang'));
              localStorage.setItem('kadro:lang', 'en');
            </script>
            </body></html>
            """.trimIndent()
    }
}
