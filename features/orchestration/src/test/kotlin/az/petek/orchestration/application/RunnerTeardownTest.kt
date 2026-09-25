package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.SharedRunState
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.campaign.domain.StepAction
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepStatus
import az.petek.identity.domain.IdentityConflictException
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRegistryGenerator
import az.petek.identity.domain.IdentitySpec
import az.petek.oracle.domain.TestCompany
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.testing.CountingFinalizer
import az.petek.orchestration.testing.DottedFieldSelector
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.TestSharedRunState
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.everyoneButAdmin
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerTeardownTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    private val seed = setupStep("seed", admin(), StepAction.Run("seed_company"))

    /** The admin's seed step creates test company c1 and shares its id; [then] runs for every later call. */
    private fun RunnerFixture.companyThen(then: suspend () -> ActionOutcome) {
        oracle.companies["c1"] = TestCompany("c1", "Pətək Test MMC", "PTK-1", isTest = true)
        agents.script = { call, runtime ->
            if (call.scenarioStep == "seed") {
                runtime.shared.put(SharedRunState.COMPANY_ID, "c1")
                ActionOutcome(ActionStatus.SUCCEEDED, "seeded")
            } else {
                then()
            }
        }
    }

    private fun RunnerFixture.shouldHaveCleanedUp(runId: RunId) {
        oracle.deleted shouldContainExactly listOf("c1")
        evidence.resourceList.filter { it.runId == runId }.shouldBeEmpty()
        browser.sessions.values.all { it.closed } shouldBe true
        browser.stops.get() shouldBe 1
        finalizer.calls shouldContainExactly listOf(runId)
        monitor.summaries shouldHaveSize 1
    }

    private suspend fun RunnerFixture.resources(runId: RunId) = evidence.resources(runId)

    @Test
    fun `teardown runs when agents throw`() =
        runTest {
            val f = fixture().apply { companyThen { throw IllegalStateException("renderer crashed") } }

            val summary = f.runner().run(campaign(setup = listOf(seed), steps = listOf(step("work", employees()))))

            summary.outcome shouldBe RunOutcome.FAILED
            f.shouldHaveCleanedUp(summary.runId)
        }

    @Test
    fun `teardown runs when the time budget runs out and the run is aborted`() =
        runTest {
            val f = fixture().apply { companyThen { awaitCancellation() } }
            val campaign = campaign(setup = listOf(seed), steps = listOf(step("work", employees()), step("later", admin())), maxMinutes = 2)

            val summary = f.runner().run(campaign, RunOptions(inactivityTimeout = 10.minutes))

            summary.outcome shouldBe RunOutcome.ABORTED
            currentTime shouldBe 120_000
            f.system("abort").single().detail!! shouldContain "time budget of 2 min exceeded"
            f.system("abort").single().detail!! shouldContain "steps not run: later"
            f.evidence.runList
                .single()
                .result shouldBe RunResult.ABORTED
            f.agents.callsFor("later").shouldBeEmpty()
            f.shouldHaveCleanedUp(summary.runId)
        }

    @Test
    fun `the remaining budget is handed to each action`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ ->
                delay(90.seconds)
                ActionOutcome(ActionStatus.SUCCEEDED, "ok")
            }

            f.runner().run(campaign(steps = listOf(step("one", admin()), step("two", admin())), maxMinutes = 5))

            f.agents.calls.map { it.context.timeout } shouldBe listOf(5.minutes, 5.minutes - 90.seconds)
        }

    @Test
    fun `teardown runs and the cancellation propagates when the run is cancelled`() =
        runTest {
            val f = fixture()
            val working = CompletableDeferred<Unit>()
            f.companyThen {
                working.complete(Unit)
                awaitCancellation()
            }
            val run = async { f.runner().run(campaign(setup = listOf(seed), steps = listOf(step("work", employees())))) }
            working.await()

            run.cancel()

            shouldThrow<CancellationException> { run.await() }
            f.oracle.deleted shouldContainExactly listOf("c1")
            f.browser.stops.get() shouldBe 1
            f.browser.sessions.values
                .all { it.closed } shouldBe true
            f.evidence.runList
                .single()
                .result shouldBe RunResult.ABORTED
            f.finalizer.calls shouldHaveSize 1
            f.system("abort").single().detail!! shouldContain "run cancelled"
        }

    @Test
    fun `keepData skips the teardown but keeps the resource registered`() =
        runTest {
            val f = fixture().apply { companyThen { ActionOutcome(ActionStatus.SUCCEEDED, "ok") } }

            val summary = f.runner().run(campaign(setup = listOf(seed)), RunOptions(keepData = true))

            f.oracle.deleted.shouldBeEmpty()
            f.resources(summary.runId).map { it.externalId } shouldBe listOf("c1")
            f.browser.stops.get() shouldBe 1
        }

    @Test
    fun `a teardown the oracle refuses is recorded without failing the run`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, runtime ->
                if (call.scenarioStep == "seed") runtime.shared.put(SharedRunState.COMPANY_ID, "real-co")
                ActionOutcome(ActionStatus.SUCCEEDED, "ok")
            }
            f.oracle.companies["real-co"] = TestCompany("real-co", "Real", null, isTest = false)

            val summary = f.runner().run(campaign(setup = listOf(seed)))

            summary.outcome shouldBe RunOutcome.PASSED
            val record = f.system("teardown").single()
            record.status shouldBe StepStatus.ERROR
            record.detail!! shouldContain "company:real-co"
            record.detail!! shouldContain "not a test company"
            f.resources(summary.runId).map { it.externalId } shouldBe listOf("real-co")
            f.monitor.events.any { it.startsWith("message teardown failed") } shouldBe true
        }

    @Test
    fun `the finalizer is called exactly once per run and its failure does not break the run`() =
        runTest {
            val f = fixture()
            val failing = CountingFinalizer(IllegalStateException("disk full"))

            val summary = f.runner(finalizer = failing).run(campaign(steps = listOf(step("work", employees()))))

            failing.calls shouldContainExactly listOf(summary.runId)
            summary.reportDirectory.shouldBeNull()
            summary.outcome shouldBe RunOutcome.PASSED
            f.monitor.summaries.single() shouldBe summary
        }

    @Test
    fun `a browser that cannot start aborts the run and still cleans up`() =
        runTest {
            val f = fixture()
            f.browser.failStart = true

            val summary = f.runner().run(campaign(steps = listOf(step("work", employees()))))

            summary.outcome shouldBe RunOutcome.ABORTED
            f.agents.calls.shouldBeEmpty()
            f.browser.stops.get() shouldBe 1
            f.system("abort").single().detail!! shouldContain "chromium could not start"
            f.finalizer.calls shouldHaveSize 1
            f.evidence.runList
                .single()
                .result shouldBe RunResult.ABORTED
        }

    @Test
    fun `an identity registry that cannot be built aborts before the browser starts`() =
        runTest {
            val f = fixture()
            val conflicting =
                object : IdentityRegistryGenerator {
                    override fun generate(
                        spec: IdentitySpec,
                        runTag: RunTag,
                    ): IdentityPlan = throw IdentityConflictException("duplicate name 'Əli'")
                }
            val runner =
                DefaultCampaignRunner(
                    identityGenerator = conflicting,
                    identities = f.identities,
                    runs = f.evidence,
                    recorder = f.evidence,
                    artifacts = f.artifacts,
                    browser = f.browser,
                    browserConfig = BrowserEngineConfig(),
                    agents = f.agents,
                    verify = f.verify,
                    oracle = f.oracle,
                    fields = DottedFieldSelector(),
                    renderer = f.renderer,
                    actors = DefaultActorResolver(),
                    monitor = f.monitor,
                    finalizer = f.finalizer,
                    clock = f.clock,
                    ids = f.ids,
                    settings = RunnerSettings("test.example.test", Path.of("build", "storage")),
                    sharedStateFactory = { TestSharedRunState() },
                )

            val summary = runner.run(campaign(steps = listOf(step("work", employees()))))

            summary.outcome shouldBe RunOutcome.ABORTED
            f.browser.starts.get() shouldBe 0
            f.browser.stops.get() shouldBe 0
            f.system("abort").single().detail!! shouldContain "duplicate name"
            f.finalizer.calls shouldHaveSize 1
        }

    @Test
    fun `a monitor that throws never breaks the run`() =
        runTest {
            val f = fixture()
            val broken =
                object : MonitorView {
                    override fun runStarted(
                        runId: RunId,
                        agents: List<AgentStatus>,
                    ): Unit = error("terminal gone")

                    override fun agentUpdated(status: AgentStatus): Unit = error("terminal gone")

                    override fun stepStarted(scenarioStep: String): Unit = error("terminal gone")

                    override fun message(text: String): Unit = error("terminal gone")

                    override fun runFinished(summary: RunSummary): Unit = error("terminal gone")
                }

            val summary =
                f
                    .runner(
                        monitor = broken,
                    ).run(campaign(setup = listOf(setupStep("join", everyoneButAdmin())), steps = listOf(step("work", employees()))))

            summary.outcome shouldBe RunOutcome.PASSED
        }

    @Test
    fun `every run gets its own id, bus and shared state`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "ok", objectId = "x") }
            val runner = f.runner()
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("announce", admin(), emits = "announcement_created"),
                            step("read", employees(), waitFor = "announcement_created"),
                        ),
                )

            val first = runner.run(campaign)
            val second = runner.run(campaign)

            first.runId shouldNotBe second.runId
            f.buses shouldHaveSize 2
            f.sharedStates shouldHaveSize 2
            f.buses.map { it.latestAny()?.eventId }.toSet() shouldHaveSize 2
            f.evidence.runList.map { it.result } shouldBe listOf(RunResult.PASSED, RunResult.PASSED)
            f.finalizer.calls shouldContainExactly listOf(first.runId, second.runId)
        }
}
