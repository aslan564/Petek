/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.application

import az.petek.llm.LlmTestData
import az.petek.llm.Outcome
import az.petek.llm.OutcomeLlmClient
import az.petek.llm.OutcomeLlmClient.Companion.fail
import az.petek.llm.OutcomeLlmClient.Companion.succeed
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetryingLlmClientTest {
    private val request = LlmTestData.request()

    private fun TestScope.scripted(vararg outcomes: Outcome) = OutcomeLlmClient(clock = { currentTime }, outcomes = outcomes.toList())

    private fun retrying(
        delegate: OutcomeLlmClient,
        maxAttempts: Int = 3,
        baseDelay: Duration = 2.seconds,
        maxDelay: Duration = 30.seconds,
        jitter: RetryJitter = RetryJitter.NONE,
    ) = RetryingLlmClient(delegate, maxAttempts, baseDelay, maxDelay, jitter)

    @Test
    fun `a successful first call is returned without waiting`() =
        runTest {
            val expected = LlmTestData.response()
            val delegate = scripted(succeed(expected))

            retrying(delegate).complete(request) shouldBeSameInstanceAs expected

            delegate.calls shouldBe 1
            currentTime shouldBe 0
        }

    @Test
    fun `transient failures are retried with exponential backoff`() =
        runTest {
            val delegate =
                scripted(fail(LlmException.Transient("502")), fail(LlmException.Transient("503")), succeed())

            retrying(delegate).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 2_000L, 6_000L)
        }

    @Test
    fun `timeouts are retried like transient failures`() =
        runTest {
            val delegate = scripted(fail(LlmException.Timeout("slow")), succeed())

            retrying(delegate).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 2_000L)
        }

    @Test
    fun `the last failure surfaces once every attempt is used`() =
        runTest {
            val last = LlmException.Transient("third")
            val delegate =
                scripted(fail(LlmException.Transient("first")), fail(LlmException.Transient("second")), fail(last))

            val thrown = shouldThrow<LlmException.Transient> { retrying(delegate).complete(request) }

            thrown shouldBeSameInstanceAs last
            delegate.calls shouldBe 3
        }

    @Test
    fun `unavailable is never retried`() =
        runTest {
            val delegate = scripted(fail(LlmException.Unavailable("not logged in")), succeed())

            shouldThrow<LlmException.Unavailable> { retrying(delegate).complete(request) }

            delegate.calls shouldBe 1
        }

    @Test
    fun `exceptions that are not LLM failures are never retried`() =
        runTest {
            val delegate = scripted(fail(IllegalStateException("bug")), succeed())

            shouldThrow<IllegalStateException> { retrying(delegate).complete(request) }

            delegate.calls shouldBe 1
        }

    @Test
    fun `invalid output is retried once and immediately`() =
        runTest {
            val delegate = scripted(fail(LlmException.InvalidOutput("not json", "oops")), succeed())

            retrying(delegate).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 0L)
        }

    @Test
    fun `invalid output is not retried a second time`() =
        runTest {
            val second = LlmException.InvalidOutput("still not json", "oops")
            val delegate = scripted(fail(LlmException.InvalidOutput("not json", "oops")), fail(second), succeed())

            val thrown = shouldThrow<LlmException.InvalidOutput> { retrying(delegate, maxAttempts = 5).complete(request) }

            thrown shouldBeSameInstanceAs second
            delegate.calls shouldBe 2
        }

    @Test
    fun `an invalid output retry does not stop later transient retries`() =
        runTest {
            val delegate =
                scripted(fail(LlmException.InvalidOutput("bad", "x")), fail(LlmException.Transient("503")), succeed())

            retrying(delegate).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 0L, 4_000L)
        }

    @Test
    fun `rate limits wait for the retry-after the provider asked for`() =
        runTest {
            val delegate = scripted(fail(LlmException.RateLimited("429", retryAfter = 10.seconds)), succeed())

            retrying(delegate).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 10_000L)
        }

    @Test
    fun `a retry-after shorter than the backoff waits for the backoff`() =
        runTest {
            val delegate = scripted(fail(LlmException.RateLimited("429", retryAfter = 1.seconds)), succeed())

            retrying(delegate, baseDelay = 3.seconds).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 3_000L)
        }

    @Test
    fun `rate limits without retry-after use the backoff`() =
        runTest {
            val delegate = scripted(fail(LlmException.RateLimited("usage limit", retryAfter = null)), succeed())

            retrying(delegate).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 2_000L)
        }

    @Test
    fun `a retry-after longer than the maximum delay is not waited for`() =
        runTest {
            val limited = LlmException.RateLimited("429", retryAfter = 5.seconds * 60)
            val delegate = scripted(fail(limited), succeed())

            val thrown = shouldThrow<LlmException.RateLimited> { retrying(delegate).complete(request) }

            thrown shouldBeSameInstanceAs limited
            delegate.calls shouldBe 1
            currentTime shouldBe 0
        }

    @Test
    fun `backoff never exceeds the maximum delay`() =
        runTest {
            val delegate =
                scripted(
                    fail(LlmException.Transient("1")),
                    fail(LlmException.Transient("2")),
                    fail(LlmException.Transient("3")),
                    succeed(),
                )

            retrying(delegate, maxAttempts = 4, baseDelay = 10.seconds, maxDelay = 15.seconds).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 10_000L, 25_000L, 40_000L)
        }

    @Test
    fun `a single attempt means no retry at all`() =
        runTest {
            val delegate = scripted(fail(LlmException.Transient("503")), succeed())

            shouldThrow<LlmException.Transient> { retrying(delegate, maxAttempts = 1).complete(request) }

            delegate.calls shouldBe 1
        }

    @Test
    fun `jitter shortens the backoff by up to half`() =
        runTest {
            val delegate = scripted(fail(LlmException.Transient("503")), succeed())

            retrying(delegate, jitter = { _, _ -> 0.5 }).complete(request)

            delegate.callTimes shouldContainExactly listOf(0L, 1_500L)
        }

    @Test
    fun `cancelling the caller during the backoff stops further attempts`() =
        runTest {
            val delegate = scripted(fail(LlmException.Transient("503")), succeed())
            val client = retrying(delegate)

            val job = launch { client.complete(request) }
            advanceTimeBy(1_000)
            runCurrent()
            job.cancel()
            advanceTimeBy(10_000)

            delegate.calls shouldBe 1
            job.isCancelled shouldBe true
        }

    @Test
    fun `provider and model are those of the wrapped client`() =
        runTest {
            val client = retrying(scripted())

            client.provider shouldBe LlmProviderKey.ANTHROPIC_API
            client.model shouldBe "outcome-model"
        }

    @Test
    fun `invalid settings are rejected`() =
        runTest {
            shouldThrow<IllegalArgumentException> { retrying(scripted(), maxAttempts = 0) }
            shouldThrow<IllegalArgumentException> { retrying(scripted(), baseDelay = (-1).seconds) }
            shouldThrow<IllegalArgumentException> {
                retrying(scripted(), baseDelay = 10.seconds, maxDelay = 5.seconds)
            }
        }

    @Test
    fun `deterministic jitter is reproducible, bounded and differs between agents`() {
        val jitter = RetryJitter.DETERMINISTIC
        val samples = (1..50).flatMap { agent -> (1..5).map { attempt -> jitter.fraction("a$agent/step", attempt) } }

        samples.forEach {
            it shouldBeGreaterThanOrEqual 0.0
            it shouldBeLessThan 1.0
        }
        jitter.fraction("a01/step", 2) shouldBe jitter.fraction("a01/step", 2)
        jitter.fraction("a01/step", 1) shouldNotBe jitter.fraction("a02/step", 1)
        samples.distinct().size shouldBe samples.size
    }

    @Test
    fun `the default jitter spreads two agents retrying the same step`() =
        runTest {
            val client = retrying(scripted(), jitter = RetryJitter.DETERMINISTIC)

            val first = client.backoff("a01/announce", attempt = 1)
            val second = client.backoff("a02/announce", attempt = 1)

            first shouldNotBe second
            (first in 1.seconds..2.seconds) shouldBe true
            (second in 1.seconds..2.seconds) shouldBe true
        }
}
