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

package az.petek.llm.application

import az.petek.llm.LlmTestData
import az.petek.llm.OutcomeLlmClient
import az.petek.llm.OutcomeLlmClient.Companion.fail
import az.petek.llm.OutcomeLlmClient.Companion.succeed
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyLimitedLlmClientTest {
    /** Holds every call until [release]; tracks how many run at once. */
    private class GatedLlmClient : LlmClient {
        override val provider = LlmProviderKey.CODEX_CLI
        override val model = "gated"
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = AtomicInteger()
        private val gate = CompletableDeferred<Unit>()
        var failWith: Exception? = null

        fun release() = gate.complete(Unit)

        override suspend fun complete(request: LlmRequest): LlmResponse {
            started.incrementAndGet()
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                gate.await()
                failWith?.let { throw it }
                return LlmTestData.response()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private val request = LlmTestData.request()

    @Test
    fun `no more than the permitted number of calls run at once`() =
        runTest {
            val delegate = GatedLlmClient()
            val client = ConcurrencyLimitedLlmClient(delegate, permits = 3)

            val calls = List(10) { async { client.complete(request) } }
            runCurrent()

            delegate.active.get() shouldBe 3
            client.availablePermits shouldBe 0

            delegate.release()
            calls.awaitAll()

            delegate.started.get() shouldBe 10
            delegate.maxActive.get() shouldBe 3
            client.availablePermits shouldBe 3
        }

    @Test
    fun `a failed call gives its permit back`() =
        runTest {
            val delegate = GatedLlmClient().apply { failWith = LlmException.Transient("503") }
            val client = ConcurrencyLimitedLlmClient(delegate, permits = 1)
            delegate.release()

            shouldThrow<LlmException.Transient> { client.complete(request) }

            client.availablePermits shouldBe 1
        }

    @Test
    fun `a caller cancelled while waiting does not take a permit`() =
        runTest {
            val delegate = GatedLlmClient()
            val client = ConcurrencyLimitedLlmClient(delegate, permits = 1)

            val running = async { client.complete(request) }
            val waiting = launch { client.complete(request) }
            runCurrent()
            waiting.cancel()
            runCurrent()
            delegate.release()
            running.await()

            delegate.started.get() shouldBe 1
            client.availablePermits shouldBe 1
        }

    @Test
    fun `a running call cancelled mid-flight releases its permit`() =
        runTest {
            val delegate = GatedLlmClient()
            val client = ConcurrencyLimitedLlmClient(delegate, permits = 1)

            val running = launch { client.complete(request) }
            runCurrent()
            client.availablePermits shouldBe 0
            running.cancel()
            runCurrent()

            client.availablePermits shouldBe 1
        }

    @Test
    fun `inside the retry decorator a call waiting for its backoff leaves the permit to others`() =
        runTest {
            val provider =
                OutcomeLlmClient(
                    clock = { currentTime },
                    outcomes = listOf(fail(LlmException.Transient("503")), succeed(), succeed()),
                )
            val client = RetryingLlmClient(ConcurrencyLimitedLlmClient(provider, permits = 1), jitter = RetryJitter.NONE)

            val first = async { client.complete(LlmTestData.request(label = "a01/step")) }
            runCurrent()
            val second = async { client.complete(LlmTestData.request(label = "a02/step")) }
            awaitAll(first, second)

            // a02 ran while a01 slept through its 2 s backoff, instead of queueing behind it.
            provider.callTimes shouldContainExactly listOf(0L, 0L, 2_000L)
        }

    @Test
    fun `provider and model are those of the wrapped client`() {
        val client = ConcurrencyLimitedLlmClient(GatedLlmClient(), permits = 2)

        client.provider shouldBe LlmProviderKey.CODEX_CLI
        client.model shouldBe "gated"
    }

    @Test
    fun `at least one permit is required`() {
        shouldThrow<IllegalArgumentException> { ConcurrencyLimitedLlmClient(GatedLlmClient(), permits = 0) }
    }
}
