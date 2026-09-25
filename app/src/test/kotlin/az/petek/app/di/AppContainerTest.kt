package az.petek.app.di

import az.petek.app.config.PetekConfig
import az.petek.app.testing.FakeBrowserEngine
import az.petek.core.security.Secret
import az.petek.core.testing.FakeHarnessClock
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderId
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.api.AnthropicApiLlmClient
import az.petek.llm.infrastructure.cli.ClaudeCliLlmClient
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
        provider: LlmProviderId = LlmProviderId.CLAUDE_CLI,
    ) = PetekConfig(
        target = URI("http://127.0.0.1:9"),
        identitySecret = Secret("container-test-identity-secret"),
        evidenceDir = dir.resolve("evidence"),
        llmConcurrency = concurrency,
        llmProvider = provider,
        anthropicApiKey = if (provider == LlmProviderId.ANTHROPIC_API) Secret("sk-ant-test") else null,
    )

    private fun request(label: String) = LlmRequest("system", listOf(LlmMessage(LlmRole.USER, "hi")), JsonObject(emptyMap()), label = label)

    /** Answers after [failures] transient errors; counts calls and the most calls in flight at once. */
    private class CountingLlm(
        private val failures: Int = 0,
        private val gate: Mutex? = null,
    ) : LlmClient {
        override val provider = LlmProviderId.CLAUDE_CLI
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
    fun `the configured provider is built when no override is given`() {
        LlmProviders.create(config()).shouldBeInstanceOf<ClaudeCliLlmClient>().model shouldBe "claude-sonnet-5"
        val api = LlmProviders.create(config(provider = LlmProviderId.ANTHROPIC_API)).shouldBeInstanceOf<AnthropicApiLlmClient>()
        api.provider shouldBe LlmProviderId.ANTHROPIC_API
        api.close()
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
