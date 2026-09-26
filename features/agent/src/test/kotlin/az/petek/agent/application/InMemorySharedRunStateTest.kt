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
    fun `values are write-once, the first publisher wins and a different later value is refused`() {
        state.get(SharedRunState.COMPANY_ID).shouldBeNull()
        state.put(SharedRunState.COMPANY_ID, "c1") shouldBe true
        state.put(SharedRunState.COMPANY_ID, "c2") shouldBe false
        state.put(SharedRunState.COMPANY_ID, "c1") shouldBe true
        state.get(SharedRunState.COMPANY_ID) shouldBe "c1"
        state.snapshot() shouldBe mapOf(SharedRunState.COMPANY_ID to "c1")
    }

    @Test
    fun `thousands of agents publishing the same key at once cannot change what the first one published`() =
        runTest {
            val results = (1..5000).map { i -> async { state.put(SharedRunState.COMPANY_CODE, "CODE-$i") } }.awaitAll()

            results.count { it } shouldBe 1
            val winner = results.indexOfFirst { it } + 1
            state.get(SharedRunState.COMPANY_CODE) shouldBe "CODE-$winner"
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
        state.put(SharedRunState.inviteLink("Eli.K7X2.a07@Test.Portal.example"), "https://x/invite/t")
        state.get(SharedRunState.inviteLink("eli.k7x2.a07@test.portal.example")) shouldBe "https://x/invite/t"
    }
}
