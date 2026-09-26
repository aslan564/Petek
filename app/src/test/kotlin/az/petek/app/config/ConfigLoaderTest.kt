/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import az.petek.browser.domain.BrowserTopology
import az.petek.core.model.WorkingLanguage
import az.petek.core.security.Secret
import az.petek.core.security.TargetVerdict
import az.petek.llm.domain.LlmProviderId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ConfigLoaderTest {
    @TempDir
    lateinit var dir: Path

    private val fileSecretReads = AtomicInteger()
    private val fileSecret =
        IdentitySecretSource {
            fileSecretReads.incrementAndGet()
            Secret("secret-from-the-petek-home-file")
        }

    private fun loader(environment: Map<String, String> = emptyMap()) = ConfigLoader(environment, dir, fileSecret)

    private fun load(vararg values: Pair<String, String>): PetekConfig = loader().fromValues(mapOf(*values))

    private fun problems(vararg values: Pair<String, String>): List<String> =
        shouldThrow<ConfigException> { loader().fromValues(mapOf(*values)) }.problems

    private val target = "PETEK_TARGET" to "https://staging.kadrohr.com"

    @Test
    fun `only the target is required and every other key has the documented default`() {
        val config = load(target)

        config.target shouldBe URI("https://staging.kadrohr.com")
        config.productionHosts shouldBe setOf("kadrohr.com", "www.kadrohr.com")
        config.allowProduction shouldBe false
        config.testToken.shouldBeNull()
        config.testApiUrl.shouldBeNull()
        config.testApiBase shouldBe config.target
        config.mailSource shouldBe MailSource.MAILPIT
        config.mailpitUrl shouldBe URI("http://localhost:8025")
        config.mailDomain shouldBe "test.kadrohr.com"
        config.llmProvider shouldBe LlmProviderId.CLAUDE_CLI
        config.llmModel shouldBe "claude-sonnet-5"
        config.claudeBin shouldBe "claude"
        config.llmConcurrency shouldBe 6
        config.anthropicApiKey.shouldBeNull()
        config.browserHeadless shouldBe true
        config.browserTopology shouldBe BrowserTopology.SHARED_SERVER
        config.browserIgnoreTlsErrors shouldBe false
        config.evidenceDir shouldBe dir.resolve("evidence")
        config.dbPath shouldBe dir.resolve("evidence/petek.db")
        config.logDirectory shouldBe dir.resolve("evidence/logs")
    }

    @Test
    fun `every key of the example file is read`() {
        val config =
            load(
                target,
                "PETEK_PRODUCTION_HOSTS" to " KadroHR.com , app.kadrohr.com ,",
                "PETEK_ALLOW_PRODUCTION" to "yes",
                "PETEK_TEST_TOKEN" to "tok-123",
                "PETEK_TEST_API_URL" to "https://api.staging.kadrohr.com/",
                "PETEK_MAIL_SOURCE" to "Test-API",
                "PETEK_MAILPIT_URL" to "http://127.0.0.1:18025",
                "PETEK_MAIL_DOMAIN" to "@QA.Example.com",
                "PETEK_IDENTITY_SECRET" to "a-long-enough-identity-secret",
                "PETEK_LLM_PROVIDER" to "anthropic-api",
                "PETEK_LLM_MODEL" to "claude-haiku-4-5",
                "PETEK_CLAUDE_BIN" to "/opt/claude/bin/claude",
                "PETEK_LLM_CONCURRENCY" to "3",
                "PETEK_LANGUAGE" to "English",
                "ANTHROPIC_API_KEY" to "sk-ant-test",
                "PETEK_BROWSER_HEADLESS" to "false",
                "PETEK_BROWSER_TOPOLOGY" to "per-session",
                "PETEK_BROWSER_IGNORE_TLS_ERRORS" to "true",
                "PETEK_EVIDENCE_DIR" to "out/evidence",
                "PETEK_DB" to "/var/tmp/petek.db",
            )

        config.productionHosts shouldBe setOf("kadrohr.com", "app.kadrohr.com")
        config.allowProduction shouldBe true
        config.testToken shouldBe Secret("tok-123")
        config.testApiUrl shouldBe URI("https://api.staging.kadrohr.com/")
        config.testApiBase shouldBe URI("https://api.staging.kadrohr.com/")
        config.mailSource shouldBe MailSource.TEST_API
        config.mailpitUrl shouldBe URI("http://127.0.0.1:18025")
        config.mailDomain shouldBe "qa.example.com"
        config.identitySecret shouldBe Secret("a-long-enough-identity-secret")
        config.llmProvider shouldBe LlmProviderId.ANTHROPIC_API
        config.language shouldBe WorkingLanguage("English")
        config.llmModel shouldBe "claude-haiku-4-5"
        config.claudeBin shouldBe "/opt/claude/bin/claude"
        config.llmConcurrency shouldBe 3
        config.anthropicApiKey shouldBe Secret("sk-ant-test")
        config.browserHeadless shouldBe false
        config.browserTopology shouldBe BrowserTopology.PER_SESSION
        config.browserIgnoreTlsErrors shouldBe true
        config.evidenceDir shouldBe dir.resolve("out/evidence")
        config.dbPath shouldBe Path.of("/var/tmp/petek.db")
        fileSecretReads.get() shouldBe 0
    }

    @Test
    fun `a blank identity secret falls back to the home file`() {
        val config = load(target, "PETEK_IDENTITY_SECRET" to "   ")

        config.identitySecret shouldBe Secret("secret-from-the-petek-home-file")
        fileSecretReads.get() shouldBe 1
    }

    @Test
    fun `real environment variables override the env file`() {
        val envFile = dir.resolve(".env").also { Files.writeString(it, "PETEK_TARGET=https://from-file.test\nPETEK_LLM_MODEL=from-file\n") }

        val config = loader(mapOf("PETEK_TARGET" to "https://from-environment.test")).load(envFile)

        config.target shouldBe URI("https://from-environment.test")
        config.llmModel shouldBe "from-file"
    }

    @Test
    fun `an absent env file is fine when the environment has the target`() {
        loader(mapOf(target)).load(dir.resolve(".env")).target shouldBe URI("https://staging.kadrohr.com")
    }

    @Test
    fun `every problem is reported at once`() {
        problems(
            "PETEK_ALLOW_PRODUCTION" to "maybe",
            "PETEK_MAILPIT_URL" to "localhost:8025",
            "PETEK_TEST_API_URL" to "api.kadrohr.com",
            "PETEK_MAIL_SOURCE" to "imap",
            "PETEK_MAIL_DOMAIN" to "not a domain",
            "PETEK_LLM_PROVIDER" to "gpt",
            "PETEK_LLM_CONCURRENCY" to "0",
            "PETEK_BROWSER_HEADLESS" to "sometimes",
            "PETEK_BROWSER_TOPOLOGY" to "cluster",
            "PETEK_PRODUCTION_HOSTS" to "kadrohr.com,bad host",
            "PETEK_IDENTITY_SECRET" to "short",
        ) shouldContainExactlyInAnyOrder
            listOf(
                "PETEK_TARGET is required (the URL of the system under test)",
                "PETEK_PRODUCTION_HOSTS contains 'bad host', which is not a host name",
                "PETEK_ALLOW_PRODUCTION must be true or false",
                "PETEK_MAILPIT_URL must be an absolute http(s) URL",
                "PETEK_TEST_API_URL must be an absolute http(s) URL",
                "PETEK_MAIL_SOURCE must be one of mailpit, test-api, was 'imap'",
                "PETEK_MAIL_DOMAIN must be a bare domain such as test.kadrohr.com",
                "PETEK_LLM_PROVIDER must be one of claude-cli, anthropic-api, was 'gpt'",
                "PETEK_LLM_CONCURRENCY must be a whole number between 1 and 64, was '0'",
                "PETEK_BROWSER_HEADLESS must be true or false",
                "PETEK_BROWSER_TOPOLOGY must be one of shared-server, per-session, was 'cluster'",
                "PETEK_IDENTITY_SECRET must be at least 16 characters long (or empty to use ~/.petek/identity.secret)",
            )
    }

    @Test
    fun `the target must be an absolute http URL without credentials`() {
        problems("PETEK_TARGET" to "ftp://staging.kadrohr.com") shouldContainExactlyInAnyOrder
            listOf("PETEK_TARGET must be an absolute http(s) URL")
        problems("PETEK_TARGET" to "https://user:hunter2@staging.kadrohr.com") shouldContainExactlyInAnyOrder
            listOf("PETEK_TARGET must not contain credentials (user:password@)")
        problems("PETEK_TARGET" to "https://exa mple.com") shouldContainExactlyInAnyOrder listOf("PETEK_TARGET is not a valid URL")
    }

    @Test
    fun `the API provider needs its key`() {
        problems(target, "PETEK_LLM_PROVIDER" to "anthropic-api") shouldContainExactlyInAnyOrder
            listOf("ANTHROPIC_API_KEY is required when PETEK_LLM_PROVIDER is anthropic-api")
    }

    @Test
    fun `the test API address is judged by the production-host policy like the target`() {
        problems(target, "PETEK_TEST_API_URL" to "https://kadrohr.com") shouldContainExactlyInAnyOrder
            listOf(
                "PETEK_TEST_API_URL: Target 'kadrohr.com' is a production host (listed in PETEK_PRODUCTION_HOSTS). " +
                    "Use a staging target, or set PETEK_ALLOW_PRODUCTION=true in .env to test it deliberately.",
            )
        load(target, "PETEK_TEST_API_URL" to "https://kadrohr.com", "PETEK_ALLOW_PRODUCTION" to "true").testApiBase shouldBe
            URI("https://kadrohr.com")
        load(target, "PETEK_TEST_API_URL" to "https://api.staging.kadrohr.com").testApiBase shouldBe URI("https://api.staging.kadrohr.com")
    }

    @Test
    fun `the test API mail source needs the test token`() {
        problems(target, "PETEK_MAIL_SOURCE" to "test-api") shouldContainExactlyInAnyOrder
            listOf("PETEK_TEST_TOKEN is required when PETEK_MAIL_SOURCE is test-api")
        load(target, "PETEK_MAIL_SOURCE" to "test-api", "PETEK_TEST_TOKEN" to "tok").mailSource shouldBe MailSource.TEST_API
    }

    @Test
    fun `a token with a line break inside is refused without echoing it`() {
        val error = shouldThrow<ConfigException> { load(target, "PETEK_TEST_TOKEN" to "abc\ndef-secret") }

        error.problems shouldContainExactlyInAnyOrder
            listOf("PETEK_TEST_TOKEN contains a control character; it cannot be sent as an HTTP header")
        error.message shouldNotContain "def-secret"
    }

    @Test
    fun `problems of the identity secret file are reported with the others`() {
        val failing = IdentitySecretSource { throw ConfigException(listOf("cannot read the identity secret")) }

        val error = shouldThrow<ConfigException> { ConfigLoader(emptyMap(), dir, failing).fromValues(emptyMap()) }

        error.problems shouldContainExactlyInAnyOrder
            listOf("PETEK_TARGET is required (the URL of the system under test)", "cannot read the identity secret")
    }

    @Test
    fun `the printed configuration never shows a secret`() {
        val config =
            load(
                "PETEK_TARGET" to "https://staging.kadrohr.com",
                "PETEK_TEST_TOKEN" to "token-value-123",
                "PETEK_IDENTITY_SECRET" to "identity-secret-value-456",
                "PETEK_LLM_PROVIDER" to "anthropic-api",
                "ANTHROPIC_API_KEY" to "sk-ant-api-key-789",
            )

        val printed = config.toString()

        printed shouldNotContain "token-value-123"
        printed shouldNotContain "identity-secret-value-456"
        printed shouldNotContain "sk-ant-api-key-789"
        printed shouldContain "testToken=set"
        printed shouldContain "anthropicApiKey=set"
        printed shouldContain "identitySecret=***"
    }

    @Test
    fun `URLs are shown without credentials`() {
        PetekConfig.masked(URI("https://user:pw@host.test/app")) shouldBe "https://***@host.test/app"
    }

    @Test
    fun `the target policy refuses production hosts unless allowed`() {
        val refusing = load("PETEK_TARGET" to "https://kadrohr.com")
        val allowing = load("PETEK_TARGET" to "https://kadrohr.com", "PETEK_ALLOW_PRODUCTION" to "true")

        refusing.targetPolicy.verify(refusing.target).shouldBeInstanceOf<TargetVerdict.Refused>()
        allowing.targetPolicy.verify(allowing.target) shouldBe TargetVerdict.Allowed
    }

    @Test
    fun `a production target spelled with capitals or a trailing dot is still refused`() {
        val config = load("PETEK_TARGET" to "HTTPS://KadroHR.com./app")

        config.target shouldBe URI("https://kadrohr.com/app")
        config.targetPolicy.verify(config.target).shouldBeInstanceOf<TargetVerdict.Refused>()
    }

    @Test
    fun `a comment after an empty value in the env file leaves the key empty`() {
        val envFile =
            dir.resolve(".env").also {
                Files.writeString(
                    it,
                    "PETEK_TARGET=https://staging.kadrohr.com\n" +
                        "PETEK_TEST_TOKEN=   # empty = oracle assertions are skipped\n" +
                        "PETEK_IDENTITY_SECRET= # empty = ~/.petek/identity.secret\n",
                )
            }

        val config = loader().load(envFile)

        config.testToken.shouldBeNull()
        config.identitySecret.reveal() shouldBe "secret-from-the-petek-home-file"
    }

    @Test
    fun `a malformed env file fails with its line numbers`() {
        val envFile = dir.resolve(".env").also { Files.writeString(it, "PETEK_TARGET=https://ok.test\nbroken line\n") }

        val error = shouldThrow<EnvFileException> { loader().load(envFile) }

        error.problems shouldContainExactlyInAnyOrder listOf("line 2: expected KEY=VALUE")
    }
}
