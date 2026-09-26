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

package az.petek.browser.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class ConfinedThreadTest {
    @Test
    fun `every block runs on the one named thread in submission order`() =
        runBlocking<Unit> {
            ConfinedThread("browser-order").use { thread ->
                val seen = CopyOnWriteArrayList<String>()

                withContext(Dispatchers.Default) {
                    (1..5)
                        .map { i ->
                            async(start = CoroutineStart.UNDISPATCHED) { thread.run { seen += "$i@${Thread.currentThread().name}" } }
                        }.forEach { it.await() }
                }

                seen shouldContainExactly (1..5).map { "$it@browser-order" }
            }
        }

    @Test
    fun `a cancelled caller stops waiting while the running block finishes on the thread`() =
        runBlocking<Unit> {
            ConfinedThread("browser-cancel").use { thread ->
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val finished = CountDownLatch(1)
                val call =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        thread.run {
                            started.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            finished.countDown()
                        }
                    }
                started.await(10, TimeUnit.SECONDS) shouldBe true

                call.cancelAndJoin()

                // The caller is released while the block is still blocked; the thread then finishes it.
                call.isCancelled shouldBe true
                finished.count shouldBe 1
                release.countDown()
                thread.run { "next" } shouldBe "next"
                finished.count shouldBe 0
            }
        }

    @Test
    fun `a timeout around a call fires on time`() =
        runBlocking<Unit> {
            ConfinedThread("browser-timeout").use { thread ->
                val release = CountDownLatch(1)

                val took =
                    measureTime {
                        shouldThrow<TimeoutCancellationException> {
                            withTimeout(100.milliseconds) { thread.run { release.await(10, TimeUnit.SECONDS) } }
                        }
                    }

                took shouldBeLessThan 5.seconds
                release.countDown()
            }
        }

    @Test
    fun `a block cancelled before it started is never run`() =
        runBlocking<Unit> {
            ConfinedThread("browser-skip").use { thread ->
                val release = CountDownLatch(1)
                val ran = CopyOnWriteArrayList<String>()
                val busy = async(start = CoroutineStart.UNDISPATCHED) { thread.run { release.await(10, TimeUnit.SECONDS) } }

                val queued = async(start = CoroutineStart.UNDISPATCHED) { thread.run { ran += "queued" } }
                queued.cancel()
                release.countDown()
                busy.await()
                thread.run { ran += "after" }

                shouldThrow<CancellationException> { queued.await() }
                ran shouldContainExactly listOf("after")
            }
        }

    @Test
    fun `runToCompletion finishes its block even when the caller is cancelled`() =
        runBlocking<Unit> {
            ConfinedThread("browser-release").use { thread ->
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val done = CopyOnWriteArrayList<String>()
                val closing =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        thread.runToCompletion {
                            started.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            done += "released"
                        }
                    }
                started.await(10, TimeUnit.SECONDS)

                closing.cancel()
                release.countDown()
                closing.join()

                done shouldContainExactly listOf("released")
            }
        }

    @Test
    fun `a closed thread rejects new blocks`() =
        runBlocking<Unit> {
            val thread = ConfinedThread("browser-closed")
            thread.close()

            shouldThrow<RejectedExecutionException> { thread.run { "too late" } }
        }
}
