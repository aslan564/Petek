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
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.infrastructure.http.StructuredMode
import az.petek.mail.infrastructure.ImapSettings
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
        config.llmProvider shouldBe LlmProviderKey.CLAUDE_CLI
        config.llmProviderReason shouldContain "no AI provider found"
        config.llmModel.shouldBeNull()
        config.effectiveLlmModel shouldBe "claude-sonnet-5"
        config.effectiveLlmBin shouldBe "claude"
        config.effectiveLlmEffort shouldBe "low"
        config.llmBaseUrl.shouldBeNull()
        config.llmStructured shouldBe StructuredMode.SCHEMA
        config.llmConcurrency shouldBe 6
        config.llmApiKey.shouldBeNull()
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
                "PETEK_LLM_EFFORT" to "medium",
                "PETEK_LLM_STRUCTURED" to "json_object",
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
        config.llmProvider shouldBe LlmProviderKey.ANTHROPIC_API
        config.llmProviderReason shouldBe "set in PETEK_LLM_PROVIDER"
        config.language shouldBe WorkingLanguage("English")
        config.llmModel shouldBe "claude-haiku-4-5"
        config.effectiveLlmBin shouldBe "/opt/claude/bin/claude"
        config.llmEffort shouldBe "medium"
        config.llmStructured shouldBe StructuredMode.JSON_OBJECT
        config.llmConcurrency shouldBe 3
        config.llmApiKey shouldBe Secret("sk-ant-test")
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
            "PETEK_MAIL_SOURCE" to "pop3",
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
                "PETEK_MAIL_SOURCE must be one of mailpit, test-api, imap, manual, was 'pop3'",
                "PETEK_MAIL_DOMAIN must be a bare domain such as test.kadrohr.com",
                "PETEK_LLM_PROVIDER must be auto or one of claude-cli, anthropic-api, codex-cli, gemini-cli, opencode-cli, openai-compat, was 'gpt'",
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
            listOf("ANTHROPIC_API_KEY (or PETEK_LLM_API_KEY) is required when PETEK_LLM_PROVIDER is anthropic-api")
    }

    @Test
    fun `an OpenAI-compatible endpoint needs its URL and a model, and its key may come from OPENAI_API_KEY`() {
        problems(target, "PETEK_LLM_PROVIDER" to "openai-compat") shouldContainExactlyInAnyOrder
            listOf(
                "PETEK_LLM_BASE_URL is required when PETEK_LLM_PROVIDER is openai-compat",
                "PETEK_LLM_MODEL is required when PETEK_LLM_PROVIDER is openai-compat (set in PETEK_LLM_PROVIDER)",
            )

        val config =
            load(
                target,
                "PETEK_LLM_PROVIDER" to "openai-compat",
                "PETEK_LLM_BASE_URL" to "http://localhost:11434/v1",
                "PETEK_LLM_MODEL" to "llama3.1",
                "OPENAI_API_KEY" to "sk-openai-0123456789",
            )

        config.llmBaseUrl shouldBe URI("http://localhost:11434/v1")
        config.llmApiKey shouldBe Secret("sk-openai-0123456789")
        config.effectiveLlmEffort.shouldBeNull()
        config.toString() shouldNotContain "sk-openai-0123456789"
    }

    @Test
    fun `auto follows the keys in the environment first`() {
        load(target, "ANTHROPIC_API_KEY" to "sk-ant-0123456789").llmProvider shouldBe LlmProviderKey.ANTHROPIC_API
        val openAi = load(target, "OPENAI_API_KEY" to "sk-0123456789abcdef", "PETEK_LLM_MODEL" to "gpt-5-mini")
        openAi.llmProvider shouldBe LlmProviderKey.OPENAI_COMPAT
        openAi.llmBaseUrl shouldBe URI("https://api.openai.com/v1")
        openAi.llmProviderReason shouldBe "auto: OPENAI_API_KEY is set"
    }

    @Test
    fun `auto follows the project's AI marker when its CLI is installed, else says why it went on`() {
        Files.writeString(dir.resolve("AGENTS.md"), "# agents")
        val installed = ConfigLoader(emptyMap(), dir, fileSecret, onPath = { it == "codex" }).fromValues(mapOf(target))
        installed.llmProvider shouldBe LlmProviderKey.CODEX_CLI
        installed.llmProviderReason shouldBe "auto: AGENTS.md found"
        installed.effectiveLlmBin shouldBe "codex"

        val missing = ConfigLoader(emptyMap(), dir, fileSecret, onPath = { it == "gemini" }).fromValues(mapOf(target))
        missing.llmProvider shouldBe LlmProviderKey.GEMINI_CLI
        missing.llmProviderReason shouldBe "auto: AGENTS.md found, but codex is not on PATH; gemini is on PATH"
    }

    @Test
    fun `PETEK_LLM_BIN wins over the old PETEK_CLAUDE_BIN and a bad structured mode is refused`() {
        load(target, "PETEK_LLM_BIN" to "/usr/local/bin/claude", "PETEK_CLAUDE_BIN" to "/old/claude").effectiveLlmBin shouldBe
            "/usr/local/bin/claude"
        problems(target, "PETEK_LLM_STRUCTURED" to "xml") shouldContainExactlyInAnyOrder
            listOf("PETEK_LLM_STRUCTURED must be one of schema, json_object, prompt, was 'xml'")
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
    fun `the owner's box gives the mail domain and IMAP reads it with the box as user by default`() {
        val config =
            load(
                target,
                "PETEK_MAIL_SOURCE" to "imap",
                "PETEK_MAIL_INBOX" to "Test@Company.az",
                "PETEK_IMAP_HOST" to "imap.company.az",
                "PETEK_IMAP_PASSWORD" to "imap-secret-123",
            )

        config.mailSource shouldBe MailSource.IMAP
        config.mailInbox shouldBe "test@company.az"
        config.mailDomain shouldBe "company.az"
        config.imap shouldBe ImapSettings("imap.company.az", "test@company.az", Secret("imap-secret-123"), 993, true, "INBOX")
        config.toString() shouldNotContain "imap-secret-123"
        load(target, "PETEK_MAIL_SOURCE" to "manual").mailSource shouldBe MailSource.MANUAL
    }

    @Test
    fun `IMAP needs a host, a user and a password, and a box must not carry its own plus tag`() {
        problems(
            target,
            "PETEK_MAIL_SOURCE" to "imap",
            "PETEK_IMAP_TLS" to "false",
            "PETEK_IMAP_PORT" to "x",
        ) shouldContainExactlyInAnyOrder
            listOf(
                "PETEK_IMAP_HOST is required when PETEK_MAIL_SOURCE is imap",
                "PETEK_IMAP_USER (or PETEK_MAIL_INBOX) is required when PETEK_MAIL_SOURCE is imap",
                "PETEK_IMAP_PASSWORD is required when PETEK_MAIL_SOURCE is imap",
                "PETEK_IMAP_PORT must be a port number, was 'x'",
            )
        problems(target, "PETEK_MAIL_INBOX" to "test+a@company.az") shouldContainExactlyInAnyOrder
            listOf("PETEK_MAIL_INBOX must be a plain e-mail address such as test@company.az (without '+')")
    }

    @Test
    fun `PETEK_TARGET may name a target profile, which then brings its site, test API, token and mail`() {
        Files.createDirectories(dir.resolve("targets"))
        Files.writeString(
            dir.resolve("targets/shop.yaml"),
            """
            target:
              name: shop
              url: https://Stage.Shop.example
              api_url: https://api.stage.shop.example
              production_hosts: [shop.example]
              mail: {source: test-api, domain: qa.shop.example}
              test_api: {token: '${'$'}{SHOP_TOKEN}'}
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("targets/blog.yaml"),
            "target: {name: blog, url: 'https://blog.example', test_api: {token: '${'$'}{BLOG_TOKEN}'}}",
        )

        val config = load("PETEK_TARGET" to "shop", "SHOP_TOKEN" to "shop-token-123")

        config.target shouldBe URI("https://stage.shop.example")
        config.testApiUrl shouldBe URI("https://api.stage.shop.example")
        config.testToken shouldBe Secret("shop-token-123")
        config.mailSource shouldBe MailSource.TEST_API
        config.mailDomain shouldBe "qa.shop.example"
        config.productionHosts shouldBe setOf("kadrohr.com", "www.kadrohr.com", "shop.example")
        config.targets.map { it.spec.name } shouldBe listOf("blog", "shop")
        config.profileFor(URI("https://blog.example/path"))?.testToken shouldBe null
        TargetProfileConfig.forTarget(config, URI("https://blog.example")).target shouldBe URI("https://blog.example")
        TargetProfileConfig.forTarget(config, URI("https://other.example")).testToken shouldBe Secret("shop-token-123")
        config.toString() shouldNotContain "shop-token-123"
        problems("PETEK_TARGET" to "shop") shouldContainExactlyInAnyOrder listOf("SHOP_TOKEN, referenced by target 'shop', is not set")
    }

    @Test
    fun `a broken target profile is a configuration problem with its file and line`() {
        Files.createDirectories(dir.resolve("targets"))
        Files.writeString(dir.resolve("targets/x.yaml"), "target:\n  name: x\n  url: nowhere\n")

        problems(target).single() shouldContain "PETEK_TARGETS_DIR (x.yaml): line 3:"
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
        printed shouldContain "llmApiKey=set"
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
