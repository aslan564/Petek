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
import az.petek.llm.OutcomeLlmClient
import az.petek.llm.OutcomeLlmClient.Companion.fail
import az.petek.llm.OutcomeLlmClient.Companion.succeed
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeteredLlmClientTest {
    private val usage = TokenUsage(inputTokens = 300, outputTokens = 40)

    @Test
    fun `a successful call is recorded with its usage and returned unchanged`() =
        runTest {
            val meter = UsageMeter()
            val response = LlmTestData.response(usage, costUsd = 0.02)
            val client = MeteredLlmClient(OutcomeLlmClient(outcomes = listOf(succeed(response))), meter)

            client.complete(LlmTestData.request(label = "a05/approve")) shouldBeSameInstanceAs response

            meter.snapshot() shouldBe mapOf("a05" to UsageTotals(calls = 1, tokens = usage, costUsd = 0.02))
        }

    @Test
    fun `a failed call is recorded as a failure and rethrown`() =
        runTest {
            val meter = UsageMeter()
            val failure = LlmException.Unavailable("not logged in")
            val client = MeteredLlmClient(OutcomeLlmClient(outcomes = listOf(fail(failure))), meter)

            val thrown = shouldThrow<LlmException.Unavailable> { client.complete(LlmTestData.request(label = "a05/x")) }

            thrown shouldBeSameInstanceAs failure
            meter.snapshot() shouldBe mapOf("a05" to UsageTotals(failedCalls = 1))
        }

    @Test
    fun `a cancelled call is not recorded`() =
        runTest {
            val meter = UsageMeter()
            val client = MeteredLlmClient(OutcomeLlmClient(outcomes = listOf({ awaitCancellation() })), meter)

            val job = launch { client.complete(LlmTestData.request()) }
            runCurrent()
            job.cancel()
            runCurrent()

            meter.snapshot().shouldBeEmpty()
        }

    @Test
    fun `the composed stack counts one call per decision however many attempts it took`() =
        runTest {
            val meter = UsageMeter()
            val provider =
                OutcomeLlmClient(
                    outcomes =
                        listOf(
                            fail(LlmException.Transient("overloaded")),
                            succeed(LlmTestData.response(usage)),
                            fail(LlmException.Unavailable("no credit")),
                        ),
                )
            val client = MeteredLlmClient(RetryingLlmClient(ConcurrencyLimitedLlmClient(provider, permits = 2)), meter)

            client.complete(LlmTestData.request(label = "a01/first"))
            shouldThrow<LlmException.Unavailable> { client.complete(LlmTestData.request(label = "a01/second")) }

            provider.calls shouldBe 3
            meter.snapshot() shouldBe
                mapOf("a01" to UsageTotals(calls = 1, failedCalls = 1, tokens = usage, costUsd = 0.01))
        }

    @Test
    fun `provider and model are those of the wrapped client`() {
        val client = MeteredLlmClient(OutcomeLlmClient(outcomes = emptyList()), UsageMeter())

        client.provider shouldBe LlmProviderKey.ANTHROPIC_API
        client.model shouldBe "outcome-model"
    }
}
