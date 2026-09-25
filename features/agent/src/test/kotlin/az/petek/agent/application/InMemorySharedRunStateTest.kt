/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.agent.application

import az.petek.agent.domain.SharedRunState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class InMemorySharedRunStateTest {
    private val state = InMemorySharedRunState()

    @Test
    fun `values can be read after they are put and a later put wins`() {
        state.get(SharedRunState.COMPANY_ID).shouldBeNull()
        state.put(SharedRunState.COMPANY_ID, "c1")
        state.put(SharedRunState.COMPANY_ID, "c2")
        state.get(SharedRunState.COMPANY_ID) shouldBe "c2"
        state.snapshot() shouldBe mapOf(SharedRunState.COMPANY_ID to "c2")
    }

    @Test
    fun `await returns a value that is already there without waiting`() =
        runTest {
            state.put(SharedRunState.COMPANY_CODE, "PTK-1")
            state.await(SharedRunState.COMPANY_CODE, Duration.ZERO) shouldBe "PTK-1"
            currentTime shouldBe 0
        }

    @Test
    fun `await suspends until the value is published`() =
        runTest {
            val waiter = async { state.await(SharedRunState.COMPANY_CODE, 5.minutes) }
            runCurrent()
            waiter.isCompleted shouldBe false

            launch {
                delay(90.seconds)
                state.put("unrelated", "x")
                delay(10.seconds)
                state.put(SharedRunState.COMPANY_CODE, "PTK-4821")
            }

            waiter.await() shouldBe "PTK-4821"
            currentTime shouldBe 100_000
        }

    @Test
    fun `await gives up after its timeout`() =
        runTest {
            state.await(SharedRunState.COMPANY_CODE, 30.seconds).shouldBeNull()
            currentTime shouldBe 30_000
        }

    @Test
    fun `many waiters are released by one publication`() =
        runTest {
            val waiters = List(30) { async { state.await(SharedRunState.COMPANY_CODE, 1.minutes) } }
            runCurrent()
            state.put(SharedRunState.COMPANY_CODE, "PTK-9")
            waiters.awaitAll().toSet() shouldBe setOf("PTK-9")
        }

    @Test
    fun `invitation links are keyed by lower-case e-mail`() {
        state.put(SharedRunState.inviteLink("Eli.K7X2.a07@Test.KadroHR.com"), "https://x/invite/t")
        state.get(SharedRunState.inviteLink("eli.k7x2.a07@test.kadrohr.com")) shouldBe "https://x/invite/t"
    }
}
