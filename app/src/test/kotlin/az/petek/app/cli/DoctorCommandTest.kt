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
import az.petek.app.testing.scriptedLlm
import az.petek.core.testing.FakeHarnessClock
import az.petek.faketarget.FakeTargetServer
import az.petek.llm.domain.LlmException
import az.petek.ownership.application.SiteOwnership
import az.petek.ownership.testing.OwnershipTestKit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DoctorCommandTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var target: FakeTargetServer
    private val healthyLlm = scriptedLlm { buildJsonObject { put("ok", true) } }

    @BeforeEach
    fun start() {
        target = FakeTargetServer().start()
    }

    @AfterEach
    fun stop() = target.close()

    private fun cli(
        vararg environment: Pair<String, String>,
        llm: az.petek.llm.domain.LlmClient = healthyLlm,
        browser: FakeBrowserEngine = FakeBrowserEngine(),
        ownership: SiteOwnership = OwnershipTestKit.owned(FakeHarnessClock()),
    ) = CliHarness(
        dir,
        mapOf(
            "PETEK_TARGET" to target.baseUrl.toString(),
            "PETEK_MAILPIT_URL" to target.mailpitUrl.toString(),
            "PETEK_TEST_TOKEN" to "dev-token",
        ) + environment,
        llm = llm,
        browser = browser,
        ownership = ownership,
    )

    /** The table row of [check], e.g. `│ ✓ │ Test inbox │ Mailpit: HTTP 200 from … │`. */
    private fun row(
        output: String,
        check: String,
    ): String = output.lines().single { " $check " in it }

    @Test
    fun `everything in place gives a green table and exit code 0`() =
        runBlocking<Unit> {
            val browser = FakeBrowserEngine()
            val result = cli(browser = browser).run("doctor")

            result.statusCode shouldBe 0
            listOf("Configuration", "Target policy", "Target reachable", "Chromium", "Test inbox", "Test API", "LLM provider").forEach {
                row(result.stdout, it) shouldContain "✓"
            }
            row(result.stdout, "Target reachable") shouldContain "HTTP 3"
            row(result.stdout, "Test inbox") shouldContain "Mailpit: HTTP 200"
            row(result.stdout, "Test API") shouldContain "token accepted (HTTP 404"
            row(result.stdout, "LLM provider") shouldContain "claude-cli (claude-sonnet-5) answered a structured request"
            browser.sessions.single().closed shouldBe true
            browser.stopCount shouldBe 1
            Files.exists(dir.resolve("evidence/petek.db")) shouldBe false
        }

    @Test
    fun `an unproved site is a failed ownership row naming the proof, and nothing is remembered`() =
        runBlocking<Unit> {
            val result = cli(ownership = OwnershipTestKit.unowned(FakeHarnessClock())).run("doctor")

            result.statusCode shouldBe 1
            val ownership = row(result.stdout, "Site ownership")
            ownership shouldContain "✗"
            ownership shouldContain "not proved"
            ownership shouldContain "/.well-known/petek-verification.txt"
            Files.exists(dir.resolve("evidence/petek.db")) shouldBe false
        }

    @Test
    fun `a local target needs no ownership proof`() =
        runBlocking<Unit> {
            val result = cli(ownership = OwnershipTestKit.unowned(FakeHarnessClock(), local = setOf("127.0.0.1"))).run("doctor")

            result.statusCode shouldBe 0
            row(result.stdout, "Site ownership") shouldContain "no proof needed"
        }

    @Test
    fun `--json prints the checks as one document with the same exit code`() =
        runBlocking<Unit> {
            val result = cli("PETEK_TEST_TOKEN" to "wrong-token").run("--json", "doctor")

            result.statusCode shouldBe 1
            val document = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            document["ok"]!!.jsonPrimitive.boolean shouldBe false
            val checks = document["checks"]!!.jsonArray.map { it.jsonObject }
            checks.map { it["name"]!!.jsonPrimitive.content } shouldContain "Test API"
            checks.single { it["name"]!!.jsonPrimitive.content == "Test API" }["status"]!!.jsonPrimitive.content shouldBe "FAILED"
            result.stdout shouldNotContain "✓"
        }

    @Test
    fun `a wrong test token is reported as rejected`() =
        runBlocking<Unit> {
            val result = cli("PETEK_TEST_TOKEN" to "wrong-token").run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Test API") shouldContain "✗"
            row(result.stdout, "Test API") shouldContain "HTTP 401: the target rejects PETEK_TEST_TOKEN"
            result.output shouldNotContain "wrong-token"
        }

    @Test
    fun `a missing test token is reported`() =
        runBlocking<Unit> {
            val cli = cli()
            cli.env.remove("PETEK_TEST_TOKEN")

            val result = cli.run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Test API") shouldContain "PETEK_TEST_TOKEN is empty"
        }

    @Test
    fun `an unreachable Mailpit and target show the exact connection error`() =
        runBlocking<Unit> {
            val result = cli("PETEK_MAILPIT_URL" to "http://127.0.0.1:9", "PETEK_TARGET" to "http://127.0.0.1:9").run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Test inbox") shouldContain "ConnectException"
            row(result.stdout, "Test inbox") shouldContain "docker compose up -d"
            row(result.stdout, "Target reachable") shouldContain "ConnectException"
            row(result.stdout, "Test API") shouldContain "connection error"
        }

    @Test
    fun `with the test API as mail source the inbox check asks the target for mail`() =
        runBlocking<Unit> {
            // The fake target has no /test/emails endpoint: the row shows exactly that, and Mailpit is not contacted.
            val result = cli("PETEK_MAIL_SOURCE" to "test-api", "PETEK_MAILPIT_URL" to "http://127.0.0.1:9").run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Test inbox") shouldContain "✗"
            row(result.stdout, "Test inbox") shouldContain "HTTP 404, the target has no GET /test/emails"
            row(result.stdout, "Test API") shouldContain "✓"
        }

    @Test
    fun `the LLM provider's own error is shown verbatim`() =
        runBlocking<Unit> {
            val broke =
                scriptedLlm {
                    throw LlmException.Unavailable(
                        "Claude CLI cannot answer: Credit balance is too low. Run `claude`, then /login with your Claude plan account.",
                    )
                }

            val result = cli(llm = broke).run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "LLM provider") shouldContain "Credit balance is too low"
            row(result.stdout, "LLM provider") shouldContain "/login"
        }

    @Test
    fun `a browser that cannot start is reported`() =
        runBlocking<Unit> {
            val result = cli(browser = FakeBrowserEngine(failStart = "Executable doesn't exist")).run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Chromium") shouldContain "Executable doesn't exist"
        }

    @Test
    fun `a refused target is not contacted`() =
        runBlocking<Unit> {
            val result = cli("PETEK_PRODUCTION_HOSTS" to "127.0.0.1").run("doctor")

            result.statusCode shouldBe 2
            row(result.stdout, "Target policy") shouldContain "production host"
            row(result.stdout, "Target reachable") shouldContain "not contacted"
            row(result.stdout, "Test API") shouldContain "not contacted"
            target.store.companies.shouldBeEmpty()
        }

    @Test
    fun `a production host spelled with a trailing dot is refused and not contacted`() =
        runBlocking<Unit> {
            val production = "http://localhost.:${target.baseUrl.port}"

            val result = cli("PETEK_PRODUCTION_HOSTS" to "localhost", "PETEK_TARGET" to production).run("doctor")

            result.statusCode shouldBe 2
            row(result.stdout, "Target policy") shouldContain "production host"
            row(result.stdout, "Target reachable") shouldContain "not contacted"
            row(result.stdout, "Test API") shouldContain "not contacted"
        }

    @Test
    fun `an invalid configuration is a failed check listing every problem`() =
        runBlocking<Unit> {
            val cli = cli("PETEK_LLM_CONCURRENCY" to "many")
            cli.env.remove("PETEK_TARGET")

            val result = cli.run("doctor")

            result.statusCode shouldBe 2
            row(result.stdout, "Configuration") shouldContain "PETEK_TARGET is required"
            row(result.stdout, "Configuration") shouldContain "PETEK_LLM_CONCURRENCY"
            row(result.stdout, "LLM provider") shouldContain "not checked"
        }
}
