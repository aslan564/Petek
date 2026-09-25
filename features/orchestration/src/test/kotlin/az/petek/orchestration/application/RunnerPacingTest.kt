package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.Pacing
import az.petek.campaign.domain.TargetProfile
import az.petek.core.ids.AgentId
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.everyoneButAdmin
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `campaign.pacing` (stagger and parallel limit) and the target's localStorage reaching every browser session. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerPacingTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    private val ok = ActionOutcome(ActionStatus.SUCCEEDED, "ok")

    private fun Campaign.paced(pacing: Pacing): Campaign = copy(settings = settings.copy(pacing = pacing))

    /** Six non-admins (a02..a07) doing one step. */
    private fun oneStep(parallel: Boolean = false) =
        campaign(steps = listOf(step("join", everyoneButAdmin(), parallel = parallel)), managers = 2, employees = 4)

    private fun TestScope.recordStarts(f: RunnerFixture): Map<AgentId, Long> {
        val startedAt = ConcurrentHashMap<AgentId, Long>()
        f.agents.script = { call, _ ->
            startedAt[call.agentId] = testScheduler.currentTime
            ok
        }
        return startedAt
    }

    @Test
    fun `actors start their actions the stagger apart in agent id order`() =
        runTest {
            val f = fixture()
            val startedAt = recordStarts(f)

            val summary = f.runner().run(oneStep().paced(Pacing(startStagger = 1500.milliseconds)))

            startedAt.toSortedMap().values.toList() shouldBe listOf(0L, 1_500L, 3_000L, 4_500L, 6_000L, 7_500L)
            startedAt.toSortedMap().keys.map { it.value } shouldBe listOf("a02", "a03", "a04", "a05", "a06", "a07")
            summary.outcome shouldBe RunOutcome.PASSED
        }

    @Test
    fun `at most the configured number of actors act at once`() =
        runTest {
            val f = fixture()
            val running = AtomicInteger()
            val maxRunning = AtomicInteger()
            f.agents.script = { _, _ ->
                maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                delay(10.seconds)
                running.decrementAndGet()
                ok
            }

            f.runner().run(oneStep().paced(Pacing(maxParallelActors = 2)))

            maxRunning.get() shouldBe 2
            currentTime shouldBe 30_000
            f.agents.calls.size shouldBe 6
        }

    @Test
    fun `stagger and limit work together`() =
        runTest {
            val f = fixture()
            val startedAt = ConcurrentHashMap<AgentId, Long>()
            f.agents.script = { call, _ ->
                startedAt[call.agentId] = testScheduler.currentTime
                delay(5.seconds)
                ok
            }

            f.runner().run(oneStep().paced(Pacing(startStagger = 1.seconds, maxParallelActors = 2)))

            // a02 0-5, a03 1-6, a04 waits for a02 (5-10), a05 for a03 (6-11), a06 (10-15), a07 (11-16).
            startedAt.toSortedMap().values.toList() shouldBe listOf(0L, 1_000L, 5_000L, 6_000L, 10_000L, 11_000L)
        }

    @Test
    fun `a parallel step keeps its start line and ignores pacing`() =
        runTest {
            val f = fixture()
            val startedAt = recordStarts(f)

            f.runner().run(oneStep(parallel = true).paced(Pacing(startStagger = 2.seconds, maxParallelActors = 1)))

            startedAt.values.toSet() shouldBe setOf(0L)
        }

    @Test
    fun `waiting for the event is not paced, so an actor whose slot has passed acts at once`() =
        runTest {
            val f = fixture()
            f.busFactory = {
                val delegate = InProcessEventBus(f.clock, f.ids)
                val waiters = AtomicInteger()
                object : EventBus by delegate {
                    override suspend fun await(
                        name: String,
                        afterSequence: Long,
                        timeout: Duration,
                    ): PublishedEvent? {
                        // The n-th waiter (a02 first) gets the event 3 s * n after the step began.
                        delay(3.seconds * waiters.getAndIncrement())
                        return delegate.await(name, afterSequence, timeout)
                    }
                }
            }
            val startedAt = recordStarts(f)
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("made", employees("IT", nth = 1), emits = "made"),
                            step("seen", managers(), waitFor = "made"),
                        ),
                ).paced(Pacing(startStagger = 1.seconds))

            f.runner().run(campaign)

            // Slots at 0 s and 1 s; the events arrive at 0 s and 3 s, so nobody waits any longer for pacing.
            val seen = f.agents.callsFor("seen").associate { it.agentId to startedAt.getValue(it.agentId) }
            seen.values.sorted() shouldBe listOf(0L, 3_000L)
        }

    @Test
    fun `the board says why an actor waits for its turn`() =
        runTest {
            val f = fixture()
            recordStarts(f)

            f.runner().run(oneStep().paced(Pacing(startStagger = 1.seconds)))

            val waiting =
                f.monitor.statuses
                    .filter { it.state == AgentState.WAITING }
                    .map { it.agentId.value to it.lastAction }
            waiting shouldContain ("a03" to "pacing: waiting for its start slot")
            waiting.none { (agent, _) -> agent == "a02" } shouldBe true
        }

    @Test
    fun `every browser session is opened with the target's local storage`() =
        runTest {
            val f = fixture()
            val storage = mapOf("kadro:domain_dialog_dismissed" to "1")
            val campaign = oneStep().let { it.copy(target = it.target.copy(localStorage = storage)) }

            f.runner().run(campaign)

            f.browser.opened
                .map { it.localStorage }
                .toSet() shouldBe setOf(storage)
            f.browser.opened.size shouldBe 7
        }

    @Test
    fun `without local storage in the profile sessions get none`() =
        runTest {
            val f = fixture()

            f.runner().run(oneStep().copy(target = TargetProfile.DEFAULT))

            f.browser.opened.all { it.localStorage.isEmpty() } shouldBe true
        }
}
