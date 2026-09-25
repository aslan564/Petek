package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.app.testing.FakeBrowserEngine
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.domain.RealtimeTransport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SmokeCommandTest {
    @TempDir
    lateinit var dir: Path

    private val browser =
        FakeBrowserEngine { session, _ ->
            session.snapshotProvider = {
                PageSnapshot(
                    url = "https://staging.kadrohr.test/login",
                    title = "KadroHR — Giriş",
                    elements =
                        listOf(
                            PageElement(1, "textbox", "E-poçt", "input", "login-email", null, true),
                            PageElement(2, "button", "Daxil ol", "button", "login-submit", null, true),
                        ),
                    visibleText = "Giriş",
                )
            }
            session.observation = NetworkObservation(setOf(RealtimeTransport.SSE), listOf("SSE GET /events"))
        }

    @Test
    fun `smoke opens the target and reports what an agent would see`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, mapOf("PETEK_TARGET" to "https://staging.kadrohr.test"), browser = browser)

            val result = cli.run("smoke")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Opened https://staging.kadrohr.test (now at https://staging.kadrohr.test/login)"
            result.stdout shouldContain "Title: KadroHR — Giriş"
            result.stdout shouldContain "Interactive elements in the snapshot: 2"
            result.stdout shouldContain "Real-time transports: sse"
            result.stdout shouldContain "SSE GET /events"
            val screenshot = cli.evidenceDir.resolve("smoke.png")
            Files.readAllBytes(screenshot).decodeToString() shouldContain "png:smoke"
            browser.sessions.single().actions shouldContain "navigate https://staging.kadrohr.test"
            browser.sessions.single().closed shouldBe true
            browser.stopCount shouldBe 1
        }

    @Test
    fun `smoke can open another URL`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, browser = browser)

            cli.run("smoke", "--url", "http://localhost:8080/login").statusCode shouldBe 0

            browser.sessions.single().actions shouldContain "navigate http://localhost:8080/login"
        }

    @Test
    fun `smoke refuses a production URL`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, browser = browser)

            val result = cli.run("smoke", "--url", "https://kadrohr.com")

            result.statusCode shouldBe 2
            result.stderr shouldContain "production host"
            browser.configs.shouldBeEmpty()
        }

    @Test
    fun `an unusable URL is a usage error`() =
        runBlocking<Unit> {
            val result = CliHarness(dir, browser = browser).run("smoke", "--url", "ftp://files.test")

            result.statusCode shouldBe 1
            result.stderr shouldContain "not an absolute http(s) URL"
        }

    @Test
    fun `a browser that cannot start exits with 1 and the reason`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, browser = FakeBrowserEngine(failStart = "Chromium could not be installed"))

            val result = cli.run("smoke")

            result.statusCode shouldBe 1
            result.stderr shouldContain "Error: Chromium could not be installed"
        }
}
