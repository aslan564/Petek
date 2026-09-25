package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.core.ids.AgentId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.actors
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.everyoneButAdmin
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.selector
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import az.petek.core.model.Role as TesterRole

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerConcurrencyTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    private val ok = ActionOutcome(ActionStatus.SUCCEEDED, "ok")

    @Test
    fun `all 29 actors of a step run at the same time`() =
        runTest {
            val f = fixture()
            val arrived = AtomicInteger()
            val running = AtomicInteger()
            val maxRunning = AtomicInteger()
            val everyoneIn = CompletableDeferred<Unit>()
            f.agents.script = { _, _ ->
                maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                if (arrived.incrementAndGet() == 29) everyoneIn.complete(Unit)
                // Only returns once all 29 are inside perform() at once: a serial runner would never get here.
                everyoneIn.await()
                running.decrementAndGet()
                ok
            }
            val campaign =
                campaign(
                    managers = 5,
                    employees = 24,
                    departments = listOf("IT", "HR", "Satış", "Maliyyə", "Əməliyyat"),
                    steps = listOf(step("login", everyoneButAdmin())),
                )

            val summary = f.runner().run(campaign)

            maxRunning.get() shouldBe 29
            f.steps("login", StepKind.DO).map { it.status }.toSet() shouldBe setOf(StepStatus.PASSED)
            f.steps("login", StepKind.DO) shouldHaveSize 29
            summary.outcome shouldBe RunOutcome.PASSED
            currentTime shouldBe 0
        }

    /** A bus whose n-th waiter needs n * 3 s to be served, so actors reach their action at different times. */
    private fun RunnerFixture.staggeredBus() {
        busFactory = {
            val delegate = InProcessEventBus(clock, ids)
            val waiters = AtomicInteger()
            object : EventBus by delegate {
                override suspend fun await(
                    name: String,
                    afterSequence: Long,
                    timeout: Duration,
                ): PublishedEvent? {
                    delay(3.seconds * waiters.getAndIncrement())
                    return delegate.await(name, afterSequence, timeout)
                }
            }
        }
    }

    private fun raceCampaign(parallel: Boolean) =
        campaign(
            steps =
                listOf(
                    step("ticket", employees("IT", nth = 1), emits = "ticket_created"),
                    step(
                        "race",
                        managers(),
                        waitFor = "ticket_created",
                        parallel = parallel,
                        assertions = listOf(AssertionSpec.OnlyOneSucceeds),
                    ),
                ),
        )

    @Test
    fun `parallel actors start their actions at the same instant`() =
        runTest {
            val f = fixture().apply { staggeredBus() }
            val startedAt = ConcurrentHashMap<AgentId, Long>()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "race") startedAt[call.agentId] = testScheduler.currentTime
                ActionOutcome(if (call.agentId == AgentId("a03")) ActionStatus.FAILED else ActionStatus.SUCCEEDED, "done")
            }

            f.runner().run(raceCampaign(parallel = true))

            startedAt shouldBe mapOf(AgentId("a02") to 3_000L, AgentId("a03") to 3_000L)
        }

    @Test
    fun `without parallel each actor starts as soon as it is ready`() =
        runTest {
            val f = fixture().apply { staggeredBus() }
            val startedAt = ConcurrentHashMap<AgentId, Long>()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "race") startedAt[call.agentId] = testScheduler.currentTime
                ok
            }

            f.runner().run(raceCampaign(parallel = false))

            startedAt.values.sorted() shouldBe listOf(0L, 3_000L)
        }

    @Test
    fun `an actor that drops out before the start line does not hold the others`() =
        runTest {
            val f = fixture()
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step(
                                "approve",
                                actors(selector(TesterRole.ADMIN), selector(TesterRole.MANAGER, "IT")),
                                StepAction.Do("Approve for {self.department}"),
                                parallel = true,
                            ),
                        ),
                )

            val summary = f.runner().run(campaign)

            f.agents.calls.map { it.agentId } shouldBe listOf(AgentId("a02"))
            f.step("approve", StepKind.DO, "a01").detail!! shouldContain "template_error"
            f.step("approve", StepKind.DO, "a02").status shouldBe StepStatus.PASSED
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `only_one_succeeds is judged once over all actors of the step`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.agentId == AgentId("a03")) ActionOutcome(ActionStatus.FAILED, "already decided") else ok
            }

            val summary = f.runner().run(raceCampaign(parallel = true))

            val results = f.verify.groupCalls.single()
            results.map { it.agentId.value to it.succeeded } shouldBe listOf("a02" to true, "a03" to false)
            results.last().summary shouldBe "already decided"
            val group = f.evidence.assertionList.single { it.type == "only_one_succeeds" }
            group.verdict shouldBe Verdict.PASSED
            group.agentId shouldBe null
            f.verify.actorCalls.none { (specs, _) -> AssertionSpec.OnlyOneSucceeds in specs } shouldBe true
            f.steps("race", StepKind.SYSTEM).single().status shouldBe StepStatus.PASSED
            summary.assertionsFailed shouldBe 0
        }

    @Test
    fun `two winners of a race fail the group assertion`() =
        runTest {
            val f = fixture()

            val summary = f.runner().run(raceCampaign(parallel = true))

            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.FAILED
            f.steps("race", StepKind.SYSTEM).single().status shouldBe StepStatus.FAILED
            summary.assertionsFailed shouldBe 1
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `the watchdog blocks a stuck agent while the others finish`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, runtime ->
                when {
                    call.scenarioStep == "work" && call.agentId == AgentId("a05") -> {
                        awaitCancellation()
                    }

                    call.scenarioStep == "work" && call.agentId == AgentId("a06") -> {
                        // Slow but alive: records evidence every 30 s for 3 minutes.
                        repeat(6) {
                            delay(30.seconds)
                            f.agentRecorder.step(progressStep(runtime.runId, call.agentId))
                        }
                        ok
                    }

                    else -> {
                        ok
                    }
                }
            }
            val campaign = campaign(steps = listOf(step("work", employees()), step("after", employees())))

            val summary = f.runner().run(campaign, RunOptions(inactivityTimeout = 60.seconds))

            val blocked = f.step("work", StepKind.DO, "a05")
            blocked.status shouldBe StepStatus.BLOCKED
            blocked.detail!! shouldContain "timeout: agent blocked (no progress for 1m)"
            blocked.durationMs shouldBe 60_000
            f.step("work", StepKind.DO, "a06").status shouldBe StepStatus.PASSED
            f.step("work", StepKind.DO, "a04").status shouldBe StepStatus.PASSED
            f.evidence.artifactList.map { it.stepId } shouldContain blocked.stepId
            f.agents.callsFor("after") shouldHaveSize 4
            f.monitor.statuses
                .filter { it.agentId == AgentId("a05") }
                .map { it.state } shouldContain AgentState.BLOCKED
            summary.stepsFailed shouldBe 1
            summary.outcome shouldBe RunOutcome.FAILED
            currentTime shouldBe 180_000
        }

    @Test
    fun `an agent that throws gets an error record and the others carry on`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.agentId == AgentId("a04") && call.scenarioStep == "work") throw IllegalStateException("page crashed") else ok
            }

            val summary = f.runner().run(campaign(steps = listOf(step("work", employees()), step("after", employees()))))

            val failed = f.step("work", StepKind.DO, "a04")
            failed.status shouldBe StepStatus.ERROR
            failed.detail shouldBe "IllegalStateException: page crashed"
            f.evidence.artifactList.map { it.stepId } shouldContain failed.stepId
            f.steps("work", StepKind.DO).count { it.status == StepStatus.PASSED } shouldBe 3
            f.agents.callsFor("after") shouldHaveSize 4
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `a permission refusal in a main step is left to the assertions`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ ->
                ActionOutcome(ActionStatus.BLOCKED, "no approve button", failureReason = FailureReason.PERMISSION_DENIED)
            }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("forbidden", employees("IT", nth = 2), assertions = listOf(AssertionSpec.NotVisible("Approve", null))),
                        ),
                )

            val summary = f.runner().run(campaign)

            val record = f.step("forbidden", StepKind.DO, "a06")
            record.status shouldBe StepStatus.BLOCKED
            record.detail shouldBe "permission_denied: no approve button"
            summary.outcome shouldBe RunOutcome.PASSED
            summary.stepsFailed shouldBe 0
            f.evidence.artifactList.map { it.stepId } shouldBe emptyList()
        }

    @Test
    fun `a permission refusal during setup fails the tester`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ ->
                ActionOutcome(ActionStatus.BLOCKED, "join refused", failureReason = FailureReason.PERMISSION_DENIED)
            }
            val join = step("join", employees("HR"), StepAction.Run("register_and_login"), phase = StepPhase.SETUP)

            val summary = f.runner().run(campaign(setup = listOf(join), steps = listOf(step("work", admin()))))

            summary.outcome shouldBe RunOutcome.FAILED
            summary.failedAgents shouldBe 2
            f.identities.statusReasons[summary.runId to AgentId("a05")] shouldBe "permission_denied"
        }

    private fun progressStep(
        runId: az.petek.core.ids.RunId,
        agentId: AgentId,
    ) = StepRecord(
        StepId("progress"),
        runId,
        agentId,
        "progress",
        StepKind.DO,
        "click [1]",
        "still working",
        Instant.EPOCH,
        Instant.EPOCH,
        0,
        StepStatus.PASSED,
        null,
        CorrelationId("cor"),
    )
}
