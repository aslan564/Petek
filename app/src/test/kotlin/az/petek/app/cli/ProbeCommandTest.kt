/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.app.testing.FakeBrowserEngine
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.RealtimeTransport
import az.petek.campaign.domain.TargetProfile
import az.petek.faketarget.FakeTargetServer
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProbeCommandTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var target: FakeTargetServer

    @BeforeEach
    fun start() {
        target = FakeTargetServer().start()
    }

    @AfterEach
    fun stop() = target.close()

    /** A browser in which every contract element is on every page, except [missing] (selector keys). */
    private fun browser(missing: Set<String> = emptySet()) =
        FakeBrowserEngine { session, _ ->
            TargetProfile.DEFAULT_SELECTORS
                .filterKeys { it !in missing }
                .values
                .forEach { session.counts[it] = 1 }
            session.observation = NetworkObservation(setOf(RealtimeTransport.SSE), listOf("EventSource /events"))
        }

    private fun cli(
        browser: FakeBrowserEngine,
        token: String? = "dev-token",
    ): CliHarness {
        val environment = mutableMapOf("PETEK_TARGET" to target.baseUrl.toString())
        token?.let { environment["PETEK_TEST_TOKEN"] = it }
        return CliHarness(dir, environment, browser = browser)
    }

    @Test
    fun `a target that fulfils the contract is ready`() =
        runBlocking<Unit> {
            val browser = browser()
            val cli = cli(browser)

            val result = cli.run("probe")

            result.statusCode shouldBe 0
            result.stdout shouldContain "ready for Pətək"
            result.stdout shouldContain "Test API without token: HTTP 401; with token: HTTP 404"
            result.stdout shouldContain "Real-time transport on the home page: sse"
            val report = Files.readString(cli.evidenceDir.resolve("probe/report.md"))
            report shouldContain "**Verdict: ready**"
            report shouldContain "| login | `/login` | HTTP 200 |"
            report shouldContain "the test API exists and requires the token"
            report shouldContain "the token is accepted"
            browser.sessions.single().actions shouldContainAll
                listOf("navigate /login", "navigate /register", "navigate /join", "navigate /verify", "navigate /")
            browser.sessions
                .single()
                .actions
                .filter { it.startsWith("fill") || it.startsWith("click") } shouldBe emptyList()
            target.store.users shouldBe emptyList()
        }

    @Test
    fun `missing contract elements are listed per page and the target is not ready`() =
        runBlocking<Unit> {
            val cli = cli(browser(missing = setOf("join.department", "register.phone")))

            val result = cli.run("probe")

            result.statusCode shouldBe 1
            result.stdout shouldContain "not ready"
            result.stdout shouldContain "join-department"
            result.stdout shouldContain "register-phone"
            val report = Files.readString(cli.evidenceDir.resolve("probe/report.md"))
            report shouldContain "**Verdict: not ready**"
            report shouldContain "`join-department`"
        }

    @Test
    fun `a verify page without the code form is tolerated because the form only exists during a sign-up`() =
        runBlocking<Unit> {
            val cli = cli(browser(missing = setOf("verify.code", "verify.submit")))

            val result = cli.run("probe")

            result.statusCode shouldBe 0
            result.stdout shouldContain "verify-code, verify-submit (not required: the code form only appears during a pending sign-up"
        }

    @Test
    fun `without a token the test API cannot be verified`() =
        runBlocking<Unit> {
            val result = cli(browser(), token = null).run("probe")

            result.statusCode shouldBe 1
            result.stdout shouldContain "not sent (PETEK_TEST_TOKEN is empty)"
        }

    @Test
    fun `the token is never sent to a URL other than the configured target`() =
        runBlocking<Unit> {
            val other = FakeTargetServer().start()
            try {
                val cli = cli(browser())

                val result = cli.run("probe", "--url", other.baseUrl.toString())

                result.statusCode shouldBe 0
                result.stdout shouldContain "with token: not sent (--url is not PETEK_TARGET"
                result.output shouldNotContain "dev-token"
            } finally {
                other.close()
            }
        }

    @Test
    fun `an unreachable site is reported page by page`() =
        runBlocking<Unit> {
            val cli = cli(browser())

            val result = cli.run("probe", "--url", "http://127.0.0.1:9")

            result.statusCode shouldBe 1
            result.stdout shouldContain "ConnectException"
            Files.readString(cli.evidenceDir.resolve("probe/report.md")) shouldContain "no answer"
        }

    @Test
    fun `a production URL is refused`() =
        runBlocking<Unit> {
            val browser = browser()

            val result = cli(browser).run("probe", "--url", "https://www.kadrohr.com")

            result.statusCode shouldBe 2
            result.stderr shouldContain "production host"
            browser.configs.size shouldBe 0
        }
}
