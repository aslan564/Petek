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

package az.petek.app.di

import az.petek.app.config.MailSource
import az.petek.app.config.PetekConfig
import az.petek.app.testing.FakeBrowserEngine
import az.petek.core.security.Secret
import az.petek.core.testing.FakeHarnessClock
import az.petek.llm.application.FallbackLlmClient
import az.petek.llm.application.UnavailableLlmClient
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.api.AnthropicApiLlmClient
import az.petek.mail.infrastructure.MailpitMailbox
import az.petek.mail.infrastructure.TestApiMailbox
import az.petek.orchestration.infrastructure.LoggingMonitorView
import az.petek.orchestration.infrastructure.NoOpMonitorView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class AppContainerTest {
    @TempDir
    lateinit var dir: Path

    private fun config(
        concurrency: Int = 6,
        provider: LlmProviderKey = LlmProviderKey.CODEX_CLI,
        fallbacks: List<LlmProviderKey> = emptyList(),
    ) = PetekConfig(
        target = URI("http://127.0.0.1:9"),
        identitySecret = Secret("container-test-identity-secret"),
        evidenceDir = dir.resolve("evidence"),
        llmConcurrency = concurrency,
        llmProvider = provider,
        llmApiKey = if (provider == LlmProviderKey.ANTHROPIC_API) Secret("sk-ant-test") else null,
        llmBaseUrl = if (provider == LlmProviderKey.OPENAI_COMPAT) URI("http://127.0.0.1:9/v1") else null,
        llmModel = if (provider in setOf(LlmProviderKey.OPENAI_COMPAT, LlmProviderKey.ANTHROPIC_API)) "model-1" else null,
        llmBin = if (provider == LlmProviderKey.CLI) "any-ai" else null,
        llmFallbacks = fallbacks,
    )

    private fun request(label: String) = LlmRequest("system", listOf(LlmMessage(LlmRole.USER, "hi")), JsonObject(emptyMap()), label = label)

    /** Answers after [failures] transient errors; counts calls and the most calls in flight at once. */
    private class CountingLlm(
        private val failures: Int = 0,
        private val gate: Mutex? = null,
    ) : LlmClient {
        override val provider = LlmProviderKey.CODEX_CLI
        override val model = "counting"
        val calls = AtomicInteger()
        val inFlight = AtomicInteger()
        var maxInFlight = 0

        override suspend fun complete(request: LlmRequest): LlmResponse {
            val call = calls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            synchronized(this) { maxInFlight = maxOf(maxInFlight, now) }
            try {
                gate?.lock()
                gate?.unlock()
                if (call <= failures) throw LlmException.Transient("overloaded")
                return LlmResponse(JsonObject(emptyMap()), TokenUsage(inputTokens = 7, outputTokens = 3), model, costUsd = null)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `nothing is created on the disk before it is used`() {
        AppContainer(config()).use { container ->
            container.config.target shouldBe URI("http://127.0.0.1:9")
        }

        Files.exists(dir.resolve("evidence")) shouldBe false
    }

    @Test
    fun `the database is created on first use and closed with the container`() =
        runBlocking<Unit> {
            val container = AppContainer(config(), AppOverrides(monitor = NoOpMonitorView))

            container.runs.latest() shouldBe null
            Files.exists(dir.resolve("evidence/petek.db")) shouldBe true

            container.close()
            container.close()
        }

    @Test
    fun `agents get a metered and retried LLM whose failures are retried transparently`() =
        runTest {
            val provider = CountingLlm(failures = 1)
            val container = AppContainer(config(), AppOverrides(llm = provider, monitor = NoOpMonitorView))

            container.llm.complete(request("a05/announce"))

            provider.calls.get() shouldBe 2
            val usage = container.usageMeter.snapshot().getValue("a05")
            usage.calls shouldBe 1
            usage.failedCalls shouldBe 0
            usage.tokens.inputTokens shouldBe 7
            container.close()
        }

    @Test
    fun `no more LLM calls than configured run at once`() =
        runTest {
            val gate = Mutex(locked = true)
            val provider = CountingLlm(gate = gate)
            val container = AppContainer(config(concurrency = 2), AppOverrides(llm = provider, monitor = NoOpMonitorView))

            val calls = (1..5).map { async { container.llm.complete(request("a0$it/x")) } }
            testScheduler.advanceUntilIdle()
            provider.inFlight.get() shouldBe 2
            gate.unlock()
            calls.awaitAll()

            provider.maxInFlight shouldBe 2
            container.close()
        }

    @Test
    fun `the doctor's LLM is the bare provider without retries`() =
        runTest {
            val provider = CountingLlm(failures = 1)
            val container = AppContainer(config(), AppOverrides(llm = provider))

            val doctorLlm = container.diagnosticLlm()

            shouldThrow<LlmException.Transient> { doctorLlm.complete(request("doctor")) }
            provider.calls.get() shouldBe 1
            (doctorLlm is AutoCloseable) shouldBe false
            container.usageMeter.snapshot() shouldBe emptyMap()
        }

    @Test
    fun `the mailbox and the oracle follow the mail source and the test API address`() {
        AppContainer(config()).use { it.mailbox.shouldBeInstanceOf<MailpitMailbox>() }
        val testApi =
            config().copy(
                testToken = Secret("tok"),
                testApiUrl = URI("http://127.0.0.1:9/api"),
                mailSource = MailSource.TEST_API,
            )
        AppContainer(testApi).use { container ->
            container.mailbox.shouldBeInstanceOf<TestApiMailbox>().toString() shouldBe "TestApiMailbox(http://127.0.0.1:9/api)"
            container.oracle.isAvailable shouldBe true
        }
    }

    @Test
    fun `the configured provider is built when no override is given`() {
        LlmProviders.create(config()).provider shouldBe LlmProviderKey.CODEX_CLI
        LlmProviders.create(config(provider = LlmProviderKey.CLI)).provider shouldBe LlmProviderKey.CLI
        LlmProviders.create(config(provider = LlmProviderKey.NONE)).shouldBeInstanceOf<UnavailableLlmClient>()
        val api = LlmProviders.create(config(provider = LlmProviderKey.ANTHROPIC_API)).shouldBeInstanceOf<AnthropicApiLlmClient>()
        api.provider shouldBe LlmProviderKey.ANTHROPIC_API
        api.close()
    }

    @Test
    fun `other AI tools found on the machine become fallbacks behind the chosen one`() {
        val client = LlmProviders.create(config(fallbacks = listOf(LlmProviderKey.GEMINI_CLI))).shouldBeInstanceOf<FallbackLlmClient>()
        client.provider shouldBe LlmProviderKey.CODEX_CLI
        client.close()
    }

    @Test
    fun `every registered provider can be built from its configuration`() {
        LlmProviders.KEYS.forEach { key ->
            val client = LlmProviders.create(config(provider = key))
            client.provider shouldBe key
            (client as? AutoCloseable)?.close()
        }
        LlmProviders.create(config(provider = LlmProviderKey.CODEX_CLI)).model shouldBe "default"
    }

    @Test
    fun `overrides replace the monitor, clock and browser`() {
        val clock = FakeHarnessClock()
        val browser = FakeBrowserEngine()

        AppContainer(config(), AppOverrides(monitor = NoOpMonitorView, clock = clock, browser = browser)).use { container ->
            container.monitor shouldBeSameInstanceAs NoOpMonitorView
            container.clock shouldBeSameInstanceAs clock
            container.browserEngine shouldBeSameInstanceAs browser
        }
        browser.stopCount shouldBe 0
    }

    @Test
    fun `explorations use a browser engine of their own unless one is given`() {
        val runs = FakeBrowserEngine()
        val explorer = FakeBrowserEngine()

        AppContainer(config(), AppOverrides(browser = runs, explorerBrowser = explorer)).use { container ->
            container.explorerBrowserEngine shouldBeSameInstanceAs explorer
            container.browserEngine shouldBeSameInstanceAs runs
        }
        AppContainer(config(), AppOverrides(browser = runs)).use { it.explorerBrowserEngine shouldBeSameInstanceAs runs }
        AppContainer(config()).use { container ->
            (container.explorerBrowserEngine === container.browserEngine) shouldBe false
        }
    }

    @Test
    fun `a container given its caller's database shares it and leaves it open`() =
        runBlocking<Unit> {
            val owner = AppContainer(config(), AppOverrides(monitor = NoOpMonitorView))
            owner.runs.latest() shouldBe null
            val other = config().copy(target = URI("http://127.0.0.2:9"), dbPath = dir.resolve("never.db"))

            AppContainer(other, AppOverrides(monitor = NoOpMonitorView, database = owner.database)).use { shared ->
                shared.database shouldBeSameInstanceAs owner.database
                shared.scenarioCatalog.list() shouldBe emptyList()
            }

            owner.runs.latest() shouldBe null
            Files.exists(dir.resolve("never.db")) shouldBe false
            owner.close()
        }

    @Test
    fun `without a terminal the monitor writes log lines`() {
        AppContainer(config()).use { container -> container.monitor.shouldBeInstanceOf<LoggingMonitorView>() }
    }

    @Test
    fun `the browser configuration follows the environment unless a run asks for a visible browser`() {
        AppContainer(config()).use { container ->
            container.browserConfig().headless shouldBe true
            container.browserConfig(headless = false).headless shouldBe false
        }
    }
}
