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

import az.petek.core.telemetry.UsageSink
import az.petek.llm.LlmTestData
import az.petek.llm.domain.TokenUsage
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class UsageMeterTest {
    private val usage = TokenUsage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 7, cacheCreationTokens = 3)

    @Test
    fun `calls are accounted to the agent id before the first slash`() {
        val meter = UsageMeter()

        meter.record("a07/announce", LlmTestData.response(usage, costUsd = 0.25))
        meter.record("a07/read/again", LlmTestData.response(usage, costUsd = 0.25))
        meter.record("a08/announce", LlmTestData.response(usage, costUsd = 0.5))

        meter.snapshot() shouldBe
            mapOf(
                "a07" to UsageTotals(calls = 2, tokens = usage + usage, costUsd = 0.5),
                "a08" to UsageTotals(calls = 1, tokens = usage, costUsd = 0.5),
            )
    }

    @Test
    fun `totals are ordered by agent number, not by text, with other labels after the agents`() {
        val meter = UsageMeter()
        listOf("a100/x", "doctor", "a02/x", "a1000/x", "a99/x", "capacity").forEach { meter.record(it, LlmTestData.response(usage)) }

        meter.snapshot().keys.toList() shouldBe listOf("a02", "a99", "a100", "a1000", "capacity", "doctor")
        meter.drain().keys.toList() shouldBe listOf("a02", "a99", "a100", "a1000", "capacity", "doctor")
    }

    @Test
    fun `a label without a slash is its own key`() {
        UsageMeter.agentKey("doctor") shouldBe "doctor"
        UsageMeter.agentKey("a01/") shouldBe "a01"
        UsageMeter.agentKey("") shouldBe ""
    }

    @Test
    fun `cost stays unknown until a call reports one`() {
        val meter = UsageMeter()

        meter.record("a01/x", LlmTestData.response(usage, costUsd = null))
        meter.snapshot().getValue("a01").costUsd shouldBe null

        meter.record("a01/y", LlmTestData.response(usage, costUsd = 0.125))
        meter.snapshot().getValue("a01").costUsd shouldBe 0.125
    }

    @Test
    fun `failed calls are counted separately from answers`() {
        val meter = UsageMeter()

        meter.recordFailure("a03/login")
        meter.record("a03/login", LlmTestData.response(usage))

        meter.snapshot().getValue("a03") shouldBe
            UsageTotals(calls = 1, failedCalls = 1, tokens = usage, costUsd = 0.01)
    }

    @Test
    fun `the total sums every agent`() {
        val meter = UsageMeter()
        meter.record("a01/x", LlmTestData.response(usage, costUsd = 0.25))
        meter.record("a02/x", LlmTestData.response(usage, costUsd = null))
        meter.recordFailure("a03/x")

        meter.total() shouldBe UsageTotals(calls = 2, failedCalls = 1, tokens = usage + usage, costUsd = 0.25)
        UsageMeter().total() shouldBe UsageTotals.EMPTY
    }

    @Test
    fun `drain hands over the totals and starts from zero`() {
        val meter = UsageMeter()
        meter.record("a01/x", LlmTestData.response(usage))

        val drained = meter.drain()

        drained.keys shouldBe setOf("a01")
        meter.snapshot().shouldBeEmpty()
        meter.record("a01/x", LlmTestData.response(usage))
        meter.drain().getValue("a01").calls shouldBe 1
    }

    @Test
    fun `draining while agents record neither loses nor duplicates a call`() {
        val meter = UsageMeter()
        val drained = mutableListOf<UsageTotals>()

        runBlocking(Dispatchers.Default) {
            repeat(30) { agent ->
                launch { repeat(500) { meter.record("a$agent/step", LlmTestData.response(usage, costUsd = 0.5)) } }
            }
            launch { repeat(200) { drained += meter.drain().values } }
        }
        val everything = (drained + meter.snapshot().values).fold(UsageTotals.EMPTY, UsageTotals::plus)

        everything.calls shouldBe 15_000L
        everything.tokens shouldBe TokenUsage(1_500_000, 300_000, 105_000, 45_000)
        everything.costUsd!! shouldBe (7_500.0 plusOrMinus 1e-6)
    }

    @Test
    fun `concurrent recording without draining counts every call`() {
        val meter = UsageMeter()

        runBlocking(Dispatchers.Default) {
            repeat(30) { agent ->
                launch { repeat(1_000) { meter.record("a${agent % 3}/step", LlmTestData.response(usage)) } }
            }
        }

        meter.snapshot().mapValues { it.value.calls } shouldBe mapOf("a0" to 10_000L, "a1" to 10_000L, "a2" to 10_000L)
        meter.total().tokens.inputTokens shouldBe 3_000_000L
    }

    @Test
    fun `the telemetry sink gets counters only, never the label`() {
        val counted = mutableListOf<Triple<String, Long, UsageSink.Tags>>()
        val meter = UsageMeter { name, amount, tags -> counted += Triple(name, amount, tags) }

        meter.record("a07/announce", LlmTestData.response(usage))
        meter.recordFailure("a07/announce")

        counted shouldBe
            listOf(
                Triple("llm.calls", 1L, UsageSink.Tags.NONE),
                Triple("llm.input_tokens", 107L, UsageSink.Tags.NONE),
                Triple("llm.output_tokens", 20L, UsageSink.Tags.NONE),
                Triple("llm.failed_calls", 1L, UsageSink.Tags.NONE),
            )
    }
}
