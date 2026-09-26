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

package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.browser.domain.BrowserActionException
import az.petek.core.ids.AgentId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class InactivityWatchdogTest {
    private val a01 = AgentId("a01")
    private val a02 = AgentId("a02")
    private val done = ActionOutcome(ActionStatus.SUCCEEDED, "done")

    @Test
    fun `an action that finishes in time returns its own outcome`() =
        runTest {
            val watchdog = InactivityWatchdog()

            watchdog.guard(a01, 30.seconds) {
                delay(10.seconds)
                done
            } shouldBe done
            currentTime shouldBe 10_000
        }

    @Test
    fun `an agent without progress is blocked after the timeout and its action is cancelled`() =
        runTest {
            val watchdog = InactivityWatchdog()
            val cancelled = CompletableDeferred<Throwable>()

            val outcome =
                watchdog.guard(a01, 30.seconds) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        cancelled.complete(e)
                        throw e
                    }
                }

            outcome.status shouldBe ActionStatus.BLOCKED
            outcome.failureReason shouldBe FailureReason.TIMEOUT
            outcome.summary shouldContain "no progress for 30s"
            cancelled.isCompleted shouldBe true
            currentTime shouldBe 30_000
        }

    @Test
    fun `progress resets the inactivity timer`() =
        runTest {
            val watchdog = InactivityWatchdog()

            val outcome =
                watchdog.guard(a01, 30.seconds) {
                    repeat(6) {
                        delay(20.seconds)
                        watchdog.progress(a01)
                    }
                    done
                }

            outcome shouldBe done
            currentTime shouldBe 120_000
            watchdog.progressCount(a01) shouldBe 6
        }

    @Test
    fun `progress stops counting once the agent goes quiet`() =
        runTest {
            val watchdog = InactivityWatchdog()

            val outcome =
                watchdog.guard(a01, 30.seconds) {
                    delay(20.seconds)
                    watchdog.progress(a01)
                    awaitCancellation()
                }

            outcome.status shouldBe ActionStatus.BLOCKED
            currentTime shouldBe 50_000
        }

    @Test
    fun `progress of another agent does not keep a stuck agent alive`() =
        runTest {
            val watchdog = InactivityWatchdog()

            val stuck = async { watchdog.guard(a01, 30.seconds) { awaitCancellation() } }
            val busy =
                async {
                    watchdog.guard(a02, 30.seconds) {
                        repeat(4) {
                            delay(15.seconds)
                            watchdog.progress(a02)
                        }
                        done
                    }
                }

            stuck.await().status shouldBe ActionStatus.BLOCKED
            busy.await() shouldBe done
        }

    @Test
    fun `progress recorded before the guard started does not count`() =
        runTest {
            val watchdog = InactivityWatchdog()
            repeat(5) { watchdog.progress(a01) }

            watchdog.guard(a01, 10.seconds) { awaitCancellation() }.status shouldBe ActionStatus.BLOCKED
            currentTime shouldBe 10_000
        }

    @Test
    fun `an action that turns the watchdog's cancellation into its own error is still blocked`() =
        runTest {
            val watchdog = InactivityWatchdog()

            val outcome =
                watchdog.guard(a01, 30.seconds) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        throw BrowserActionException("page closed while clicking", e)
                    }
                }

            outcome.status shouldBe ActionStatus.BLOCKED
            outcome.failureReason shouldBe FailureReason.TIMEOUT
            currentTime shouldBe 30_000
        }

    @Test
    fun `an action that swallows the watchdog's cancellation is still blocked`() =
        runTest {
            val watchdog = InactivityWatchdog()

            val outcome =
                watchdog.guard(a01, 30.seconds) {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        ActionOutcome(ActionStatus.ERROR, "interrupted")
                    }
                }

            outcome.status shouldBe ActionStatus.BLOCKED
        }

    @Test
    fun `the caller's own cancellation wins over a block that already timed out`() =
        runTest {
            val watchdog = InactivityWatchdog()
            val blockedAndWrapping = CompletableDeferred<Unit>()
            val guarded =
                async {
                    watchdog.guard(a01, 30.seconds) {
                        try {
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            blockedAndWrapping.complete(Unit)
                            // Still cleaning up when the caller is cancelled as well.
                            withContext(NonCancellable) { delay(5.seconds) }
                            throw BrowserActionException("page closed", e)
                        }
                    }
                }
            blockedAndWrapping.await()

            guarded.cancel()

            shouldThrow<CancellationException> { guarded.await() }
        }

    @Test
    fun `exceptions of the action propagate unchanged`() =
        runTest {
            val watchdog = InactivityWatchdog()

            shouldThrow<IllegalStateException> {
                watchdog.guard(a01, 30.seconds) { throw IllegalStateException("browser crashed") }
            }.message shouldBe "browser crashed"
        }

    @Test
    fun `cancelling the caller cancels the action and is not reported as blocked`() =
        runTest {
            val watchdog = InactivityWatchdog()
            val guarded = async { watchdog.guard(a01, 30.seconds) { awaitCancellation() } }
            delay(5.seconds)

            guarded.cancel()

            shouldThrow<CancellationException> { guarded.await() }
        }

    @Test
    fun `a non-positive or infinite timeout disables the watchdog`() =
        runTest {
            val watchdog = InactivityWatchdog()

            watchdog.guard(a01, Duration.ZERO) {
                delay(10.minutes)
                done
            } shouldBe done
            watchdog.guard(a01, Duration.INFINITE) {
                delay(10.minutes)
                done
            } shouldBe done
        }
}
