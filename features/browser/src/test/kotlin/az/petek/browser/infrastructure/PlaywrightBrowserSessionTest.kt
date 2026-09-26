/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.BrowserTopology
import az.petek.browser.domain.DialogType
import az.petek.browser.domain.RealtimeTransport
import az.petek.browser.domain.SessionOptions
import az.petek.core.time.SystemHarnessClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** Real Chromium through one shared browser server; pages come from [TestSite]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaywrightBrowserSessionTest {
    private val clock = SystemHarnessClock()
    private val site = TestSite()
    private val engine = PlaywrightBrowserEngine(clock)
    private lateinit var sessions: BrowserSessionFactory

    @BeforeAll
    fun startEngine() =
        runBlocking<Unit> {
            sessions = engine.start(BrowserEngineConfig(topology = BrowserTopology.SHARED_SERVER))
        }

    @AfterAll
    fun stopEngine() {
        runBlocking { engine.stop() }
        site.close()
    }

    private fun withSession(
        defaultTimeout: Duration = 5.seconds,
        block: suspend (BrowserSession) -> Unit,
    ) = runBlocking<Unit> {
        val session = sessions.open(SessionOptions("tester", site.baseUrl, defaultTimeout = defaultTimeout))
        try {
            block(session)
        } finally {
            session.close()
        }
    }

    @Test
    fun `snapshot numbers the visible interactive elements in DOM order with their accessible names`() =
        withSession { session ->
            session.navigate("/form")
            val snapshot = session.snapshot()

            snapshot.title shouldBe "Forma"
            snapshot.url shouldBe "${site.baseUrl}/form"
            snapshot.elements.map { Triple(it.ref, it.role, it.name) } shouldContainExactly
                listOf(
                    Triple(1, "textbox", "Ad"),
                    Triple(2, "textbox", "E-poçt"),
                    Triple(3, "textbox", "Şifrə"),
                    Triple(4, "combobox", "Şöbə"),
                    Triple(5, "textbox", "Qeyd"),
                    Triple(6, "checkbox", "Razıyam"),
                    Triple(7, "button", "Göndər"),
                    Triple(8, "button", "Deaktiv"),
                    Triple(9, "button", "Menyu"),
                    Triple(10, "link", "Profil"),
                )
            snapshot.elements[0].testId shouldBe "name-input"
            snapshot.elements[6].testId shouldBe "submit"
            snapshot.elements[7].enabled shouldBe false
            snapshot.elements[3].value shouldBe "—"
            snapshot.elements[5].value shouldBe "unchecked"
            snapshot.visibleText shouldContain "Qeydiyyat"
            snapshot.visibleText shouldNotContain "Gizli mətn"
            snapshot.render() shouldContain "[7] button \"Göndər\" (testid=submit)"
        }

    @Test
    fun `snapshot tags elements with their refs and reassigns them on every snapshot`() =
        withSession { session ->
            session.navigate("/dynamic")
            session.snapshot().elements.map { it.name } shouldContainExactly listOf("Əlavə et")
            session.readAttribute("#add", PlaywrightBrowserSession.REF_ATTRIBUTE) shouldBe "1"

            session.click(1)
            val after = session.snapshot()

            after.elements.map { it.ref to it.name } shouldContainExactly listOf(1 to "Yeni", 2 to "Əlavə et")
            session.readAttribute("#add", PlaywrightBrowserSession.REF_ATTRIBUTE) shouldBe "2"
            session.count("[${PlaywrightBrowserSession.REF_ATTRIBUTE}]") shouldBe 2
        }

    @Test
    fun `password values never leave the adapter`() =
        withSession { session ->
            session.navigate("/secret")
            session.fillSelector("#current", "typed-secret-42")
            session.fillSelector("#new", "brand-new-secret-7")

            val snapshot = session.snapshot()
            snapshot.elements.map { it.value } shouldContainExactly listOf("******", "******", "******", null)
            val everything =
                listOf(snapshot.render(), snapshot.visibleText, session.domSnapshot(), session.accessibilitySnapshot())
                    .joinToString("\n")
            everything shouldNotContain "typed-secret-42"
            everything shouldNotContain "brand-new-secret-7"
            everything shouldNotContain "server-rendered-secret"
            session.accessibilitySnapshot() shouldContain "textbox \"Şifrə\": ******"
        }

    @Test
    fun `a typed password stays masked after the page reveals or echoes it`() =
        withSession { session ->
            session.navigate("/secret")
            session.fillSelector("#current", "typed-secret-42")
            session.clickSelector("#reveal")

            val snapshot = session.snapshot()

            snapshot.elements.single { it.name == "Şifrə" }.value shouldBe "******"
            snapshot.visibleText shouldContain "Daxil etdiyiniz şifrə: ******"
            val everything =
                listOf(snapshot.render(), session.domSnapshot(), session.accessibilitySnapshot(), session.readText("#echo"))
                    .joinToString("\n")
            everything shouldNotContain "typed-secret-42"
        }

    @Test
    fun `a typed password submitted in the address is masked in the URL`() =
        withSession { session ->
            session.navigate("/secret")
            val password =
                session
                    .snapshot()
                    .elements
                    .single { it.name == "Şifrə" }
                    .ref

            session.fill(password, "typed-secret-42", submit = true)

            session.waitForText("Forma göndərildi", 3.seconds).found shouldBe true
            session.currentUrl() shouldBe "${site.baseUrl}/secret?password=******"
            session.snapshot().url shouldBe "${site.baseUrl}/secret?password=******"
        }

    @Test
    fun `text typed into ordinary fields is shown as it is`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot()
            session.fill(1, "Aysel")

            session.snapshot().elements[0].value shouldBe "Aysel"
            session.domSnapshot() shouldContain "Qeydiyyat"
        }

    @Test
    fun `a short password is masked in the accessibility snapshot without touching other text`() =
        withSession { session ->
            session.navigate("/secret")
            session.fillSelector("#current", "ş")

            val aria = session.accessibilitySnapshot()

            aria shouldContain "textbox \"Şifrə\": ******"
            aria shouldContain "Köhnə şifrə"
        }

    @Test
    fun `an empty password field is reported as empty rather than masked`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot().elements[2].value shouldBe ""
        }

    @Test
    fun `click fill and select by ref drive the page`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot()

            session.fill(1, "Aysel")
            session.select(4, "Satış")
            session.click(6)
            session.click(7)

            session.readText("#result") shouldBe "Göndərildi: Aysel / sales"
            val snapshot = session.snapshot()
            snapshot.elements[0].value shouldBe "Aysel"
            snapshot.elements[3].value shouldBe "Satış"
            snapshot.elements[5].value shouldBe "checked"
        }

    @Test
    fun `fill with submit presses Enter`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot()

            session.fill(1, "Rəşad", submit = true)

            session.waitForText("Göndərildi: Rəşad", 2.seconds).found shouldBe true
        }

    @Test
    fun `select matches the option label first and then the option value`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot()

            session.select(4, "mkt")
            session.snapshot().elements[3].value shouldBe "Marketinq"

            session.select(4, "satış")
            session.snapshot().elements[3].value shouldBe "Satış"
        }

    @Test
    fun `selecting an unknown option lists the options that exist`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot()

            val failure = shouldThrow<BrowserActionException> { session.select(4, "Maliyyə") }

            failure.message shouldContain "option \"Maliyyə\" not found"
            failure.message shouldContain "\"Marketinq\""
        }

    @Test
    fun `a ref that is not on the page asks for a new snapshot`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot()
            session.navigate("/dynamic")

            val failure = shouldThrow<BrowserActionException> { session.click(7) }

            failure.message shouldBe "element 7 not found, take a new snapshot"
        }

    @Test
    fun `selector functions read and drive the page`() =
        withSession { session ->
            session.navigate("/form")

            session.fillSelector("[data-testid=name-input]", "Nigar")
            session.selectSelector("#dept", "Marketinq")
            session.clickSelector("[data-testid=submit]")

            session.readText("#result") shouldBe "Göndərildi: Nigar / mkt"
            session.readAttribute("[data-testid=profile-link]", "href") shouldBe "/me"
            session.readText("#missing").shouldBeNull()
            session.readAttribute("#missing", "href").shouldBeNull()
            session.count("button") shouldBe 3
            session.isSelectorVisible("[data-testid=submit]") shouldBe true
            session.isSelectorVisible("button[style]") shouldBe false
            session.isTextVisible("Qeydiyyat") shouldBe true
            session.isTextVisible("Gizli mətn") shouldBe false
        }

    @Test
    fun `clicking by selector follows links and navigate resolves paths against the base URL`() =
        withSession { session ->
            session.navigate("/form")
            session.clickSelector("text=Profil")

            session.waitForSelector("#greeting", 2.seconds).found shouldBe true
            session.currentUrl() shouldBe "${site.baseUrl}/me"
        }

    @Test
    fun `a snapshot right after a click that navigates does not fail`() =
        withSession { session ->
            session.navigate("/form")
            session.snapshot()

            session.click(10)
            session.snapshot()

            session.waitForSelector("#greeting", 3.seconds).found shouldBe true
            session.snapshot().title shouldBe "Profil"
        }

    @Test
    fun `waitForText returns at once when the text is already visible`() =
        withSession { session ->
            session.navigate("/form")
            val before = clock.now()

            val outcome = session.waitForText("Qeydiyyat", 5.seconds)

            outcome.found shouldBe true
            val observedAt = outcome.observedAt.shouldNotBeNull()
            before.elapsedUntil(observedAt) shouldBeLessThan 2.seconds
        }

    @Test
    fun `waitForText observes the moment the slow page shows its text`() =
        withSession { session ->
            val t0 = clock.now()
            session.navigate("/slow")

            val outcome = session.waitForText("Hazırdır", 5.seconds)

            outcome.found shouldBe true
            val observedAt = outcome.observedAt.shouldNotBeNull()
            val latency = t0.elapsedUntil(observedAt)
            latency shouldBeGreaterThanOrEqualTo 1.seconds
            latency shouldBeLessThan 4.seconds
            // The page stamped the moment it showed the text; the harness saw it within one probe interval or so.
            val shownAtEpochMillis = session.readAttribute("#ready", "data-shown-at").shouldNotBeNull().toDouble()
            val lagMillis = observedAt.wall.toEpochMilli() - shownAtEpochMillis
            lagMillis shouldBeGreaterThanOrEqualTo -50.0
            lagMillis shouldBeLessThan 300.0
        }

    @Test
    fun `waitForText gives up after the timeout without throwing`() =
        withSession { session ->
            session.navigate("/form")
            val before = clock.now()

            val outcome = session.waitForText("Gizli mətn", 400.milliseconds)

            outcome.found shouldBe false
            outcome.observedAt.shouldBeNull()
            before.elapsedUntil(clock.now()) shouldBeGreaterThanOrEqualTo 400.milliseconds
        }

    @Test
    fun `waitForSelector reports found and timeout and a zero timeout only checks once`() =
        withSession { session ->
            session.navigate("/slow")

            session.waitForSelector("#ready", 0.seconds).found shouldBe false
            session.waitForSelector("#ready", 3.seconds).found shouldBe true
            session.waitForSelector("#never", 200.milliseconds).found shouldBe false
            session.waitForText("Hazırdır", 0.seconds).found shouldBe true
        }

    @Test
    fun `waits work on a page whose content security policy forbids eval`() =
        withSession { session ->
            session.navigate("/csp")

            session.waitForText("Salam", 2.seconds).found shouldBe true
            session.waitForText("Gec mətn", 3.seconds).found shouldBe true
            session.waitForSelector("#late", 1.seconds).found shouldBe true
            session.waitForText("heç vaxt", 200.milliseconds).found shouldBe false
            session.isTextVisible("Gec mətn") shouldBe true
            session.snapshot().visibleText shouldContain "Gec mətn"
        }

    @Test
    fun `text inside an open shadow root is seen like any other text`() =
        withSession { session ->
            session.navigate("/shadow")

            session.waitForText("Kölgədə bildiriş", 3.seconds).found shouldBe true
            session.isTextVisible("Kölgədə bildiriş") shouldBe true
            session.waitForSelector(".toast", 1.seconds).found shouldBe true
            session.isTextVisible("Gizli kölgə") shouldBe false
            session.isTextVisible("color: teal") shouldBe false
            val snapshot = session.snapshot()
            snapshot.elements.map { it.name } shouldContainExactly listOf("Bağla")
            snapshot.visibleText shouldBe "Adi mətn\nKölgədə bildiriş\nBağla"
        }

    @Test
    fun `a caller cancelled during a long wait is released at once and the session keeps working`() =
        withSession { session ->
            session.navigate("/form")

            val took =
                measureTime {
                    shouldThrow<TimeoutCancellationException> {
                        withTimeout(300.milliseconds) { session.waitForText("heç vaxt görünməyəcək", 2.seconds) }
                    }
                }

            took shouldBeLessThan 1.seconds
            session.isTextVisible("Qeydiyyat") shouldBe true
        }

    @Test
    fun `an unbounded wait still waits instead of timing out at once`() =
        withSession { session ->
            session.navigate("/slow")

            session.waitForText("Hazırdır", Duration.INFINITE).found shouldBe true
            session.waitForSelector("#ready", Duration.INFINITE).found shouldBe true
        }

    @Test
    fun `two sessions have separate cookies and local storage`() =
        runBlocking<Unit> {
            val ali = sessions.open(SessionOptions("ali", site.baseUrl))
            val vali = sessions.open(SessionOptions("vali", site.baseUrl))
            val guest = sessions.open(SessionOptions("guest", site.baseUrl))
            try {
                login(ali, "ali")
                login(vali, "vali")
                guest.navigate("/me")

                ali.readText("#greeting") shouldBe "Salam, ali"
                ali.readText("#local") shouldBe "local=ali"
                vali.readText("#greeting") shouldBe "Salam, vali"
                vali.readText("#local") shouldBe "local=vali"
                guest.readText("#greeting") shouldBe "Anonim"
                guest.readText("#local") shouldBe "local=-"
            } finally {
                listOf(ali, vali, guest).forEach { it.close() }
            }
        }

    @Test
    fun `a saved storage state logs a new session in`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        val state = dir.resolve("states/nested/ali.json")
        val first = sessions.open(SessionOptions("first", site.baseUrl))
        try {
            login(first, "ali")
            first.saveStorageState(state)
        } finally {
            first.close()
        }
        state.exists() shouldBe true

        val second = sessions.open(SessionOptions("second", site.baseUrl, storageState = state))
        try {
            second.navigate("/me")
            second.readText("#greeting") shouldBe "Salam, ali"
            second.readText("#local") shouldBe "local=ali"
        } finally {
            second.close()
        }
    }

    @Test
    fun `a missing storage state file is reported before anything is started`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        val failure =
            shouldThrow<BrowserActionException> {
                sessions.open(SessionOptions("lost", site.baseUrl, storageState = dir.resolve("absent.json")))
            }

        failure.message shouldContain "absent.json does not exist"
    }

    @Test
    fun `request carries the session cookies and does not follow redirects`() =
        withSession { session ->
            session.request("GET", "/api/me").status shouldBe 401

            login(session, "leyla")

            session.request("GET", "/api/me").let {
                it.status shouldBe 200
                it.body shouldBe "leyla"
            }
            session.request("get", "${site.baseUrl}/api/me").body shouldBe "leyla"
            session.request("GET", "/redirect").status shouldBe 302
            session.request("POST", "/api/echo", """{"a":1}""").let {
                it.status shouldBe 201
                it.body shouldBe """application/json|{"a":1}"""
            }
        }

    @Test
    fun `server-sent events are detected from network traffic`() =
        withSession { session ->
            session.navigate("/sse")
            session.waitForText("Yeni elan", 3.seconds).found shouldBe true

            val observation = session.networkObservation()

            observation.transports shouldBe setOf(RealtimeTransport.SSE)
            observation.details shouldContainExactly listOf("SSE ${site.baseUrl}/events")
        }

    @Test
    fun `polling is detected from repeated regular requests`() =
        withSession { session ->
            session.navigate("/polling")
            session.waitForText("poll 5 done", 5.seconds).found shouldBe true

            val observation = session.networkObservation()

            observation.transports shouldBe setOf(RealtimeTransport.POLLING)
            observation.details shouldHaveSize 1
            observation.details.single() shouldStartWith "Polling GET ${site.baseUrl}/api/poll every ~"
        }

    @Test
    fun `websocket use is detected even when the handshake fails`() =
        withSession { session ->
            session.navigate("/ws")
            session.waitForText("closed", 3.seconds).found shouldBe true

            val observation = session.networkObservation()

            observation.transports shouldBe setOf(RealtimeTransport.WEBSOCKET)
            observation.details shouldContainExactly listOf("WebSocket ws://127.0.0.1:${site.baseUrl.port}/socket")
        }

    @Test
    fun `a page without live updates shows no transport`() =
        withSession { session ->
            session.navigate("/form")

            val observation = session.networkObservation()

            observation.transports.shouldBeEmpty()
            observation.details.shouldBeEmpty()
        }

    @Test
    fun `screenshot is a PNG of the viewport`() =
        withSession { session ->
            session.navigate("/form")

            val png = session.screenshot()

            val image = ImageIO.read(ByteArrayInputStream(png)).shouldNotBeNull()
            image.width shouldBe 1280
            image.height shouldBe 800
        }

    @Test
    fun `accessibility and DOM snapshots describe the page`() =
        withSession { session ->
            session.navigate("/form")

            session.accessibilitySnapshot() shouldContain "button \"Göndər\""
            session.domSnapshot() shouldStartWith "<!DOCTYPE html>"
            session.domSnapshot() shouldContain "data-testid=\"submit\""
        }

    @Test
    fun `the context uses the Azerbaijani locale and the Baku time zone`() =
        withSession { session ->
            session.navigate("/env")

            session.readText("#env") shouldBe "az-AZ|Asia/Baku"
        }

    @Test
    fun `only web pages can be opened so local files never reach a snapshot`() =
        withSession { session ->
            listOf("file:///etc/hostname", " FILE:///etc/hostname", "fi\tle:///etc/hostname", "chrome://version", "view-source:/form")
                .forEach { address ->
                    shouldThrow<BrowserActionException> { session.navigate(address) }.message shouldContain
                        "only http(s) addresses and paths are allowed"
                }
            session.currentUrl() shouldBe "about:blank"

            session.navigate("/form")
            session.navigate("${site.baseUrl}/me")
            session.navigate("about:blank")
            session.navigate("//127.0.0.1:${site.baseUrl.port}/form")
            session.currentUrl() shouldBe "${site.baseUrl}/form"
        }

    @Test
    fun `navigation failures are reported as browser action failures`() =
        withSession { session ->
            val closedPort = ServerSocket(0).use { it.localPort }
            val url = "http://127.0.0.1:$closedPort/"

            val failure = shouldThrow<BrowserActionException> { session.navigate(url) }

            failure.message shouldBe
                "navigate to $url failed: net::ERR_CONNECTION_REFUSED at $url (navigating to \"$url\", waiting until \"load\")"
        }

    @Test
    fun `a failed fill never repeats the typed text`() =
        withSession(defaultTimeout = 300.milliseconds) { session ->
            session.navigate("/form")

            val failure =
                shouldThrow<BrowserActionException> {
                    session.fillSelector("#does-not-exist", "super-secret-password")
                }

            failure.message shouldNotContain "super-secret-password"
            failure.message shouldContain "fill #does-not-exist failed"
        }

    @Test
    fun `close is idempotent and a closed session refuses further work`() =
        runBlocking<Unit> {
            val session = sessions.open(SessionOptions("closing", site.baseUrl)) as PlaywrightBrowserSession
            session.threadName shouldBe "browser-closing"
            liveThreadNames() shouldContain "browser-closing"

            session.close()
            session.close()

            shouldThrow<BrowserActionException> { session.snapshot() }.message shouldBe "browser session 'closing' is closed"
            liveThreadNames().filter { it == "browser-closing" }.shouldBeEmpty()
        }

    @Test
    fun `a call still queued when the session closes fails as closed`() =
        runBlocking<Unit> {
            val session = sessions.open(SessionOptions("queued", site.baseUrl))
            session.navigate("/form")
            // UNDISPATCHED hands each call to the session thread in this order: the wait runs, the snapshot queues.
            // The wait is long and the close comes after a pause, so on a slow machine the session thread has
            // picked up the wait (it must be running, not queued) and has not finished it when the close arrives.
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.waitForText("heç vaxt", 10.seconds) }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.snapshot() } }
            delay(300.milliseconds)

            session.close()

            waiting.await().found shouldBe false
            queued.await().exceptionOrNull()?.message shouldBe "browser session 'queued' is closed"
        }

    @Test
    fun `a confirm dialog is accepted and reported once with its type and message`() =
        withSession { session ->
            session.navigate("/dialogs")
            val before = clock.now()

            session.clickSelector("#confirm")

            session.readText("#answer") shouldBe "silindi"
            val dialogs = session.drainDialogs()
            dialogs.map { it.type to it.message } shouldContainExactly listOf(DialogType.CONFIRM to "Bileti silək?")
            (dialogs.single().at.monotonicNanos >= before.monotonicNanos) shouldBe true
            session.drainDialogs().shouldBeEmpty()
        }

    @Test
    fun `a prompt gets its default text and every dialog is reported in the order it opened`() =
        withSession { session ->
            session.navigate("/dialogs")

            session.clickSelector("#prompt")
            session.readText("#answer") shouldBe "ad=Əli"
            session.clickSelector("#alert")

            session.readText("#answer") shouldBe "bağlandı"
            val dialogs = session.drainDialogs()
            dialogs.map { it.type to it.message } shouldContainExactly
                listOf(DialogType.PROMPT to "Adınız?", DialogType.ALERT to "Yadda saxlandı")
            (dialogs[0].at.monotonicNanos <= dialogs[1].at.monotonicNanos) shouldBe true
        }

    @Test
    fun `a dialog echoing a typed password shows it masked`() =
        withSession { session ->
            session.navigate("/dialogs")
            session.fillSelector("#pw", "Gizli-Parol-77")

            session.clickSelector("#echo")

            session.readText("#answer") shouldBe "göstərildi"
            val message = session.drainDialogs().single().message
            message shouldBe "Şifrəniz: ${SecretRedactor.MASK}"
            message shouldNotContain "Gizli-Parol-77"
        }

    @Test
    fun `a page without dialogs reports none`() =
        withSession { session ->
            session.navigate("/form")
            session.clickSelector("[data-testid=submit]")

            session.drainDialogs().shouldBeEmpty()
        }

    @Test
    fun `a form post is recorded with the redirect that accepted it and a repeat with the refusal`() =
        withSession { session ->
            session.navigate("/ticket?id=form-race")
            val start = clock.now()

            session.clickSelector("#approve")
            session.currentUrl() shouldContain "approved=1"
            session.clickSelector("#approve")

            session.waitForText("Bu müraciət artıq qərarlaşdırılıb", 3.seconds).found shouldBe true
            val mutations = session.mutations(start)
            mutations.map { it.describe() } shouldContainExactly
                listOf("POST /tickets/form-race/approve -> 303", "POST /tickets/form-race/approve -> 409")
            mutations.forEach { it.at.monotonicNanos shouldBeGreaterThanOrEqualTo start.monotonicNanos }
            (mutations[0].at.monotonicNanos <= mutations[1].at.monotonicNanos) shouldBe true
        }

    @Test
    fun `fetch calls that change something are recorded with their answers and reads are not`() =
        withSession { session ->
            session.navigate("/ticket?id=api-race")
            val start = clock.now()

            listOf("#api" to "call 1: POST 200", "#api" to "call 2: POST 409", "#update" to "call 3: PUT 200")
                .plus(listOf("#delete" to "call 4: DELETE 403", "#read" to "call 5: GET 401"))
                .forEach { (button, result) ->
                    session.clickSelector(button)
                    session.waitForText(result, 3.seconds).found shouldBe true
                }

            session.mutations(start).map { it.describe() } shouldContainExactly
                listOf(
                    "POST /api/tickets/api-race/approve -> 200",
                    "POST /api/tickets/api-race/approve -> 409",
                    "PUT /api/tickets/api-race -> 200",
                    "DELETE /api/tickets/api-race -> 403",
                )
        }

    @Test
    fun `only the requests answered since the given time are returned`() =
        withSession { session ->
            session.navigate("/ticket?id=window")
            session.clickSelector("#api")
            session.waitForText("call 1: POST 200", 3.seconds).found shouldBe true
            // Catch up on everything answered so far, then mark the start of the next action.
            session.mutations(clock.now())
            val actionStart = clock.now()

            session.clickSelector("#api")
            session.waitForText("call 2: POST 409", 3.seconds).found shouldBe true

            session.mutations(actionStart).map { it.describe() } shouldContainExactly listOf("POST /api/tickets/window/approve -> 409")
        }

    @Test
    fun `page loads and the session's own probes are not the page's mutations`() =
        withSession { session ->
            val start = clock.now()
            session.navigate("/ticket?id=probe")

            session.request("POST", "/api/echo", "{}").status shouldBe 201
            session.request("POST", "/api/tickets/probe/approve").status shouldBe 200

            session.mutations(start).shouldBeEmpty()
        }

    private suspend fun login(
        session: BrowserSession,
        user: String,
    ) {
        session.navigate("/login")
        session.fillSelector("[data-testid=user]", user)
        session.clickSelector("[data-testid=login]")
        session.waitForText("Salam, $user", 3.seconds).found shouldBe true
    }

    private fun liveThreadNames(): List<String> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive }
            .map { it.name }
}
