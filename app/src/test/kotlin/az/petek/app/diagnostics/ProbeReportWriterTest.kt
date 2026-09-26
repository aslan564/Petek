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

package az.petek.app.diagnostics

import az.petek.browser.domain.RealtimeTransport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ProbeReportWriterTest {
    @TempDir
    lateinit var dir: Path

    private val probedAt = Instant.parse("2026-09-25T10:00:00Z")

    private fun page(
        key: String,
        http: HttpCheck = HttpCheck.Answered(200, null),
        missing: List<String> = emptyList(),
        error: String? = null,
        note: String? = null,
    ) = PageProbe(key, "/$key", http, "https://t.test/$key", listOf("[data-testid=\"$key-submit\"]"), missing, error, note)

    private fun report(
        pages: List<PageProbe> = listOf(page("login")),
        anonymous: HttpCheck = HttpCheck.Answered(401, null),
        token: TestApiProbe.TokenCheck = TestApiProbe.TokenCheck.Answered(404),
        realtime: RealtimeProbe = RealtimeProbe(setOf(RealtimeTransport.WEBSOCKET), listOf("ws://t.test/live"), null),
    ) = ProbeReport(URI("https://t.test"), pages, TestApiProbe(anonymous, token), realtime)

    @Test
    fun `a complete target is ready`() {
        val markdown = ProbeReportWriter.markdown(report(), probedAt)

        report().ready shouldBe true
        markdown shouldContain "# Target readiness: https://t.test"
        markdown shouldContain "Probed at 2026-09-25T10:00:00Z"
        markdown shouldContain "**Verdict: ready**"
        markdown shouldContain "| login | `/login` | HTTP 200 | https://t.test/login | `login-submit` | - |"
        markdown shouldContain "the test API exists and requires the token"
        markdown shouldContain "HTTP 404 — the token is accepted"
        markdown shouldContain "websocket"
        markdown shouldContain "- ws://t.test/live"
    }

    @Test
    fun `capabilities name the test API, mail, live updates, a CAPTCHA and a rate limit`() {
        val pages =
            listOf(
                page("login").copy(captcha = "recaptcha"),
                page("register", http = HttpCheck.Answered(429, null)),
            )
        val ready = TargetCapabilities.of(report(pages), "imap")
        val absent = TargetCapabilities.of(report(anonymous = HttpCheck.Answered(404, null)), "manual")
        val noToken = TargetCapabilities.of(report(token = TestApiProbe.TokenCheck.NotSent("no token", false)), "mailpit")

        ready.summary() shouldBe
            "test API var və token qəbul olunur; poçt: imap; real-time: websocket; CAPTCHA: login (recaptcha); rate limit (429): register"
        absent.testApi shouldBe TargetCapabilities.TestApiSupport.ABSENT
        noToken.testApi shouldBe TargetCapabilities.TestApiSupport.GUARDED_TOKEN_MISSING
        ProbeReportWriter.markdown(report(pages), probedAt, ready) shouldContain "A CAPTCHA blocks self-registration"
    }

    @Test
    fun `CAPTCHA widgets are recognised by their markers`() {
        CaptchaSigns.detect("<div class=\"g-recaptcha\" data-sitekey=\"x\">") shouldBe "recaptcha"
        CaptchaSigns.detect("<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\">") shouldBe "turnstile"
        CaptchaSigns.detect("<div class=\"h-captcha\">") shouldBe "hcaptcha"
        CaptchaSigns.detect("<form><input name=\"email\"></form>") shouldBe null
    }

    @Test
    fun `missing elements, redirects and errors make a page not ready`() {
        val pages =
            listOf(
                page("register", missing = listOf("[data-testid=\"register-phone\"]")),
                page("join", http = HttpCheck.Answered(302, "/login")),
                page("verify", error = "BrowserActionException: timeout | late"),
            )

        val markdown = ProbeReportWriter.markdown(report(pages = pages), probedAt)

        pages.map { it.ready } shouldBe listOf(false, false, false)
        markdown shouldContain "**Verdict: not ready**"
        markdown shouldContain "`register-phone`"
        markdown shouldContain "HTTP 302 → /login"
        markdown shouldContain "error: BrowserActionException: timeout \\| late"
    }

    @Test
    fun `a note explains an expected gap`() {
        val pages = listOf(page("verify", missing = listOf("[data-testid=\"verify-code\"]"), note = "only during sign-up"))

        ProbeReportWriter.markdown(report(pages = pages), probedAt) shouldContain "> verify: only during sign-up"
    }

    @Test
    fun `missing elements of a page that only shows them within a flow do not block readiness`() {
        val verify =
            PageProbe(
                "verify",
                "/verify",
                HttpCheck.Answered(200, null),
                null,
                emptyList(),
                listOf("x"),
                null,
                "flow",
                elementsRequired = false,
            )

        verify.ready shouldBe true
        verify.copy(http = HttpCheck.Answered(500, null)).ready shouldBe false
        ProbeReportWriter.markdown(report(pages = listOf(verify)), probedAt) shouldContain "| `x` (not required) |"
        report(pages = listOf(verify)).ready shouldBe true
    }

    @Test
    fun `a test API without a token guard is flagged`() {
        val markdown = ProbeReportWriter.markdown(report(anonymous = HttpCheck.Answered(200, null)), probedAt)

        report(anonymous = HttpCheck.Answered(200, null)).ready shouldBe false
        markdown shouldContain "answers WITHOUT a token"
    }

    @Test
    fun `a missing test API is explained`() {
        ProbeReportWriter.markdown(report(anonymous = HttpCheck.Answered(404, null)), probedAt) shouldContain
            "the target has no test API or does not run in test mode"
    }

    @Test
    fun `a rejected token is explained`() {
        val markdown = ProbeReportWriter.markdown(report(token = TestApiProbe.TokenCheck.Answered(401)), probedAt)

        markdown shouldContain "the token is rejected; check PETEK_TEST_TOKEN"
    }

    @Test
    fun `a token that was deliberately not sent does not block readiness`() {
        val notSent = TestApiProbe.TokenCheck.NotSent("another URL", acceptable = true)

        report(token = notSent).ready shouldBe true
        ProbeReportWriter.markdown(report(token = notSent), probedAt) shouldContain "not sent: another URL"
    }

    @Test
    fun `no detected transport is explained`() {
        val markdown = ProbeReportWriter.markdown(report(realtime = RealtimeProbe(emptySet(), emptyList(), null)), probedAt)

        markdown shouldContain "None detected"
    }

    @Test
    fun `the report is written to report md in the directory`() {
        val file = ProbeReportWriter.write(report(), probedAt, dir.resolve("probe"))

        file shouldBe dir.resolve("probe/report.md")
        Files.readString(file) shouldBe ProbeReportWriter.markdown(report(), probedAt)
        Files.list(dir.resolve("probe")).use { it.count() } shouldBe 1L
    }

    @Test
    fun `test ids are shown without the selector syntax`() {
        ProbeReportWriter.testIdOf("[data-testid=\"login-email\"]") shouldBe "login-email"
        ProbeReportWriter.testIdOf("#custom") shouldBe "#custom"
    }
}
