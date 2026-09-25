/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.di

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.llm.application.UsageMeter
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.TokenUsage
import az.petek.orchestration.application.RunFinalizer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UsageFlushingFinalizerTest {
    private val runId = RunId("run_1")
    private val meter = UsageMeter()
    private val evidence = InMemoryEvidence()
    private val finalized = mutableListOf<RunId>()
    private val usageSeenByDelegate = mutableListOf<List<UsageRecord>>()
    private val delegate =
        RunFinalizer { id ->
            finalized += id
            usageSeenByDelegate += evidence.usage(id)
            Path.of("evidence", id.value, "report")
        }

    private fun response(
        input: Long,
        output: Long,
        cost: Double?,
    ) = LlmResponse(JsonObject(emptyMap()), TokenUsage(inputTokens = input, outputTokens = output, cacheReadTokens = 5), "m", cost)

    @Test
    fun `usage is recorded per agent before the report is written`() =
        runTest {
            meter.record("a01/owner_signup", response(100, 10, 0.25))
            meter.record("a01/announce", response(50, 5, 0.5))
            meter.recordFailure("a01/announce")
            meter.record("a07/read", response(30, 3, null))

            val report = UsageFlushingFinalizer(meter, evidence, delegate).finalize(runId)

            report shouldBe Path.of("evidence", "run_1", "report")
            finalized shouldContainExactly listOf(runId)
            usageSeenByDelegate.single() shouldContainExactlyInAnyOrder
                listOf(
                    UsageRecord(runId, AgentId("a01"), 150, 15, 10, 0.75, calls = 3),
                    UsageRecord(runId, AgentId("a07"), 30, 3, 5, null, calls = 1),
                )
        }

    @Test
    fun `each run records only its own usage`() =
        runTest {
            val finalizer = UsageFlushingFinalizer(meter, evidence, delegate)
            meter.record("a02/step", response(10, 1, null))
            finalizer.finalize(RunId("run_1"))
            meter.record("a02/step", response(20, 2, null))

            finalizer.finalize(RunId("run_2"))

            evidence.usage(RunId("run_1")).single().inputTokens shouldBe 10
            evidence.usage(RunId("run_2")).single().inputTokens shouldBe 20
        }

    @Test
    fun `usage that belongs to no agent is not attributed to the run`() =
        runTest {
            meter.record("doctor", response(1, 1, null))
            meter.record("harness/judge", response(1, 1, null))

            UsageFlushingFinalizer(meter, evidence, delegate).finalize(runId)

            evidence.usage(runId).shouldBeEmpty()
            finalized shouldContainExactly listOf(runId)
        }

    @Test
    fun `a failing usage store does not cost the report`() =
        runTest {
            val broken =
                object : EvidenceRecorder by evidence {
                    override suspend fun usage(record: UsageRecord): Unit = throw IllegalStateException("disk full")
                }
            meter.record("a01/x", response(1, 1, null))

            UsageFlushingFinalizer(meter, broken, delegate).finalize(runId)

            finalized shouldContainExactly listOf(runId)
        }

    @Test
    fun `a failing report still propagates after usage was stored`() =
        runTest {
            meter.record("a01/x", response(1, 1, null))
            val failing = RunFinalizer { throw IllegalStateException("writer broken") }

            shouldThrow<IllegalStateException> { UsageFlushingFinalizer(meter, evidence, failing).finalize(runId) }

            evidence.usage(runId).single().agentId shouldBe AgentId("a01")
        }
}
