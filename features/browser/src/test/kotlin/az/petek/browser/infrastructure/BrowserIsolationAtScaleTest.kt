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
import az.petek.browser.domain.BrowserTopology
import az.petek.browser.domain.SessionOptions
import az.petek.core.time.SystemHarnessClock
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Proof, with real Chromium, that N concurrent sessions on shared browser servers never see each other: each logs in
 * as a different user, then every session — all at the same time, twice — reads back who the site thinks it is (the
 * cookie), what its page shows, what its localStorage holds and which thread serves it. Saved storage states hold only
 * the session's own cookie. `petek.isolation.sessions` sets N (default 30; the machine's memory is the limit, see
 * docs/requirements/R01 for the recorded runs). Real browser, so tagged `e2e`: `./gradlew :features:browser:isolationTest`.
 */
@Tag("e2e")
class BrowserIsolationAtScaleTest {
    @TempDir
    lateinit var dir: Path

    private val clock = SystemHarnessClock()
    private val site = TestSite()
    private val engine = PlaywrightBrowserEngine(clock)
    private val sessionsWanted = System.getProperty("petek.isolation.sessions")?.toIntOrNull() ?: DEFAULT_SESSIONS

    @AfterEach
    fun cleanUp() {
        runBlocking { engine.stop() }
        site.close()
    }

    @Test
    fun `many sessions log in as different users at once and none sees another's cookie, page, storage or thread`() =
        runBlocking<Unit> {
            val n = sessionsWanted
            val factory = engine.start(BrowserEngineConfig(topology = BrowserTopology.SHARED_SERVER))
            val sessions = mutableListOf<BrowserSession>()
            try {
                val opening = measureTime { sessions += openAll(factory, n) }
                println("[isolation] $n sessions opened in $opening on ${engine.browserServers().size} browser server(s)")
                engine.sessionsPerServer().sum() shouldBe n
                engine.sessionsPerServer().forEach { it shouldBeLessThanOrEqual BrowserEngineConfig().contextsPerBrowser }

                withContext(Dispatchers.Default) {
                    sessions.mapIndexed { i, session -> async { login(session, user(i)) } }.awaitAll()
                    repeat(2) { pass ->
                        val seen = sessions.map { session -> async { observe(session) } }.awaitAll()
                        seen.forEachIndexed { i, report ->
                            withClue("pass $pass, session ${user(i)}") {
                                report.apiUser shouldBe user(i)
                                report.greeting shouldBe "Salam, ${user(i)}"
                                report.localStorage shouldBe "local=${user(i)}"
                                USER_TOKEN.findAll(report.pageText).map { it.value }.toSet() shouldBe setOf(user(i))
                            }
                        }
                        seen.map { it.thread }.toSet() shouldHaveSize n
                    }
                    sessions
                        .mapIndexed { i, session ->
                            async {
                                val file = dir.resolve("state-${i + 1}.json")
                                session.saveStorageState(file)
                                val cookies = COOKIE_VALUE.findAll(Files.readString(file)).map { it.groupValues[1] }.toList()
                                withClue("storage state of ${user(i)}") { cookies shouldBe listOf(user(i)) }
                            }
                        }.awaitAll()
                }
            } finally {
                withContext(Dispatchers.Default) { sessions.map { async { it.close() } }.awaitAll() }
            }
            engine.sessionsPerServer().sum() shouldBe 0
        }

    private suspend fun openAll(
        factory: BrowserSessionFactory,
        n: Int,
    ): List<BrowserSession> =
        withContext(Dispatchers.Default) {
            (1..n).chunked(OPEN_BATCH).flatMap { batch ->
                batch.map { i -> async { factory.open(SessionOptions("iso-$i", site.baseUrl)) } }.awaitAll()
            }
        }

    private suspend fun login(
        session: BrowserSession,
        user: String,
    ) {
        session.navigate("/login")
        val snapshot = session.snapshot()
        session.fill(snapshot.elements.single { it.testId == "user" }.ref, user)
        session.click(snapshot.elements.single { it.testId == "login" }.ref)
        session.waitForText("Salam, $user", 30.seconds).found shouldBe true
    }

    private data class Observation(
        val thread: String,
        val greeting: String?,
        val apiUser: String,
        val localStorage: String?,
        val pageText: String,
    )

    private suspend fun observe(session: BrowserSession): Observation {
        session.navigate("/me")
        return Observation(
            thread = (session as PlaywrightBrowserSession).threadName,
            greeting = session.readText("#greeting"),
            apiUser = session.request("GET", "/api/me").body,
            localStorage = session.readText("#local"),
            pageText = session.snapshot().visibleText,
        )
    }

    private fun user(index: Int) = "user${index + 1}"

    private inline fun withClue(
        clue: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue(clue, block)

    private companion object {
        const val DEFAULT_SESSIONS = 30
        const val OPEN_BATCH = 10
        val COOKIE_VALUE = Regex(""""name"\s*:\s*"session"\s*,\s*"value"\s*:\s*"([^"]+)"""")

        /** Every user name the page mentions; a session's page may mention its own user only. */
        val USER_TOKEN = Regex("""\buser\d+\b""")
    }
}
