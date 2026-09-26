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
        withSession(mapOf("portal:lang" to "az", "portal:domain_dialog_dismissed" to "1")) { session ->
            session.navigate("/storage")

            session.readText("#seen") shouldBe "portal:lang=az; portal:domain_dialog_dismissed=1"
        }

    @Test
    fun `values the page changed are seeded again on the next page load`() =
        withSession(mapOf("portal:lang" to "az")) { session ->
            session.navigate("/storage")
            session.navigate("/storage")

            session.readText("#seen")!! shouldContain "portal:lang=az"
        }

    @Test
    fun `another origin does not receive the target's values`() =
        withSession(mapOf("portal:lang" to "az")) { session ->
            session.navigate("http://localhost:$port/storage")

            session.readText("#seen") shouldBe "portal:lang=null; portal:domain_dialog_dismissed=null"
        }

    @Test
    fun `values with quotes, backslashes and line breaks arrive unchanged`() =
        withSession(mapOf("portal:lang" to "a\"b\\c\nd")) { session ->
            session.navigate("/storage")

            session.readText("#raw") shouldBe "\"a\\\"b\\\\c\\nd\""
        }

    @Test
    fun `without values nothing is seeded`() =
        withSession(emptyMap()) { session ->
            session.navigate("/storage")

            session.readText("#seen") shouldBe "portal:lang=null; portal:domain_dialog_dismissed=null"
        }

    @Test
    fun `the script is only built when there is something to seed and names the target origin`() {
        LocalStorageSeed.script(SessionOptions("a", baseUrl)).shouldBeNull()
        val script = LocalStorageSeed.script(SessionOptions("a", URI("https://Portal.example:443/app"), localStorage = mapOf("k" to "v")))!!
        script shouldContain "location.origin !== \"https://portal.example\""
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
              const keys = ['portal:lang', 'portal:domain_dialog_dismissed'];
              document.getElementById('seen').textContent = keys.map(k => k + '=' + localStorage.getItem(k)).join('; ');
              document.getElementById('raw').textContent = JSON.stringify(localStorage.getItem('portal:lang'));
              localStorage.setItem('portal:lang', 'en');
            </script>
            </body></html>
            """.trimIndent()
    }
}
