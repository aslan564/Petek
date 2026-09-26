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

import az.petek.core.ids.AgentId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.core.time.SystemHarnessClock
import az.petek.orchestration.testing.VirtualClock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class InProcessEventBusTest {
    private val admin = AgentId("a01")
    private val employee = AgentId("a02")

    private fun TestScope.bus() = InProcessEventBus(VirtualClock(testScheduler), SequentialIdGenerator())

    @Test
    fun `publish assigns ids, increasing sequence numbers and the harness time as t0`() =
        runTest {
            val bus = bus()
            val first = bus.publish("announcement_created", "a1", admin)
            delay(2.seconds)
            val second = bus.publish("ticket_created", "t1", employee)

            first.sequence shouldBe 1
            second.sequence shouldBe 2
            first.eventId.value shouldBe "evt_1"
            first.t0.elapsedUntil(second.t0) shouldBe 2.seconds
            second.emitter shouldBe employee
            bus.history().map { it.name } shouldContainExactly listOf("announcement_created", "ticket_created")
        }

    @Test
    fun `a late waiter gets an event that was published before it started waiting`() =
        runTest {
            val bus = bus()
            val published = bus.publish("announcement_created", "a1", admin)
            delay(10.seconds)

            val received = bus.await("announcement_created", afterSequence = 0, timeout = 1.seconds)

            received shouldBe published
            currentTime shouldBe 10_000
        }

    @Test
    fun `an early waiter suspends until the event is published`() =
        runTest {
            val bus = bus()
            val waiter = async { bus.await("announcement_created", afterSequence = 0, timeout = 30.seconds) }
            delay(3.seconds)
            bus.publish("other_event", "x", admin)
            delay(2.seconds)
            val published = bus.publish("announcement_created", "a1", admin)

            waiter.await() shouldBe published
            currentTime shouldBe 5_000
        }

    @Test
    fun `many waiters are all woken by one publish`() =
        runTest {
            val bus = bus()
            val waiters = (1..29).map { async { bus.await("announcement_created", 0, 30.seconds) } }
            delay(1.seconds)
            val published = bus.publish("announcement_created", "a1", admin)

            waiters.map { it.await() }.toSet() shouldBe setOf(published)
        }

    @Test
    fun `await returns the latest event of that name`() =
        runTest {
            val bus = bus()
            bus.publish("ticket_created", "t1", employee)
            val latest = bus.publish("ticket_created", "t2", employee)
            bus.publish("announcement_created", "a1", admin)

            bus.await("ticket_created", 0, 1.seconds) shouldBe latest
            bus.latest("ticket_created") shouldBe latest
            bus.latestAny()?.objectId shouldBe "a1"
        }

    @Test
    fun `afterSequence ignores events that were already seen`() =
        runTest {
            val bus = bus()
            val old = bus.publish("ticket_created", "t1", employee)
            val waiter = async { bus.await("ticket_created", afterSequence = old.sequence, timeout = 30.seconds) }
            launch {
                delay(4.seconds)
                bus.publish("ticket_created", "t2", employee)
            }

            val next = waiter.await().shouldNotBeNull()
            next.objectId shouldBe "t2"
            currentTime shouldBe 4_000
        }

    @Test
    fun `await times out with null when nothing arrives`() =
        runTest {
            val bus = bus()
            bus.publish("other_event", "x", admin)

            bus.await("announcement_created", 0, 30.seconds).shouldBeNull()
            currentTime shouldBe 30_000
        }

    @Test
    fun `a zero timeout only looks at what is already there`() =
        runTest {
            val bus = bus()
            bus.await("announcement_created", 0, Duration.ZERO).shouldBeNull()
            val published = bus.publish("announcement_created", null, admin)
            bus.await("announcement_created", 0, Duration.ZERO) shouldBe published
            currentTime shouldBe 0
        }

    @Test
    fun `an empty bus has no latest events`() =
        runTest {
            val bus = bus()
            bus.latest("anything").shouldBeNull()
            bus.latestAny().shouldBeNull()
            bus.history() shouldBe emptyList()
        }

    @Test
    fun `concurrent publishers get unique, gap-free sequence numbers in t0 order`() =
        runTest {
            val bus = bus()
            val events = (1..50).map { i -> async { bus.publish("e${i % 3}", "$i", admin) } }.map { it.await() }

            events.map { it.sequence }.sorted() shouldBe (1L..50L).toList()
            events.map { it.eventId }.toSet().size shouldBe 50
            bus.history().zipWithNext().all { (a, b) -> a.sequence < b.sequence && a.t0.monotonicNanos <= b.t0.monotonicNanos } shouldBe
                true
        }

    @Test
    fun `publishers and waiters on many threads agree on order, t0 and the latest event`() =
        runTest {
            val bus = InProcessEventBus(SystemHarnessClock(), SequentialIdGenerator())

            val (published, awaited) =
                withContext(Dispatchers.Default) {
                    val waiters = (1..20).map { async { bus.await("e0", afterSequence = 0, timeout = 10.seconds) } }
                    val publishers = (1..400).map { i -> async { bus.publish("e${i % 4}", "$i", AgentId.of(i % 30 + 1)) } }
                    publishers.awaitAll() to waiters.awaitAll()
                }

            published.map { it.sequence }.sorted() shouldBe (1L..400L).toList()
            published.map { it.eventId }.toSet() shouldHaveSize 400
            val history = bus.history()
            history.map { it.sequence } shouldBe (1L..400L).toList()
            history.zipWithNext().all { (a, b) -> a.t0.monotonicNanos <= b.t0.monotonicNanos } shouldBe true
            awaited.all { it != null && it.name == "e0" } shouldBe true
            bus.latest("e0") shouldBe history.last { it.name == "e0" }
            bus.latestAny() shouldBe history.last()
        }
}
