package az.petek.app.panel

import az.petek.app.testing.FakeBrowserEngine
import az.petek.app.testing.PanelHarness
import az.petek.app.testing.PanelHarness.Companion.tinyCampaign
import az.petek.app.testing.PanelLlm
import az.petek.app.testing.PanelWaits
import az.petek.app.testing.PanelWaits.ended
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.ScenarioSource
import az.petek.dashboard.domain.ScenarioStatus
import az.petek.dashboard.domain.TaskState
import az.petek.dashboard.domain.TriageCategory
import az.petek.evidence.domain.RunResult
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.scenarios.domain.ScenarioVersionId
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Runs, history, reports, stability and triage from the panel, with production wiring and no browser or LLM. */
class PanelRunsTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()

    @AfterEach
    fun close() = open.forEach { it.close() }

    private fun harness(
        llm: PanelLlm = PanelLlm(),
        runs: FakeBrowserEngine = FakeBrowserEngine(),
    ): PanelHarness =
        PanelHarness(dir, site = PanelWaits.site(), runs = runs, llm = llm, scenarios = mapOf("tiny.yaml" to tinyCampaign())).also {
            open +=
                it
        }

    private suspend fun PanelHarness.approved(): String = backend.scenarios().single { it.status == ScenarioStatus.APPROVED }.id

    @Test
    fun `an approved scenario runs with the chosen tester count and fills the board, the orchestrator, the history and the report`() =
        runBlocking<Unit> {
            val runs = FakeBrowserEngine()
            val panel = harness(runs = runs)
            val scenario = panel.approved()

            val started = panel.backend.startRun(RunRequest(scenarioId = scenario, testers = 3))

            started.testers shouldBe 3
            started.scenarioId shouldBe scenario
            val board = panel.ended(started.runId)
            board.run.outcome shouldBe RunOutcome.PASSED
            board.agents shouldHaveSize 3
            val orchestrator = panel.panel.dashboard.orchestrator()
            val plan = orchestrator.plan.shouldNotBeNull()
            plan.runId shouldBe started.runId
            plan.steps.map { it.id to it.agentIds.map { agent -> agent.value } } shouldContainExactly
                listOf("signup" to listOf("a01"), "look" to listOf("a02", "a03"))
            orchestrator.tasks.map { Triple(it.stepId, it.agentId.value, it.state) } shouldContainExactly
                listOf(
                    Triple("signup", "a01", TaskState.PASSED),
                    Triple("look", "a02", TaskState.PASSED),
                    Triple("look", "a03", TaskState.PASSED),
                )
            orchestrator.taskCounts[TaskState.PENDING] shouldBe 0
            runs.sessions shouldHaveSize 3

            val history = panel.backend.runs().single()
            history.runId shouldBe started.runId
            history.result shouldBe RunResult.PASSED
            history.testers shouldBe 3
            history.scenarioId shouldBe scenario
            history.reportAvailable shouldBe true
            history.triaged shouldBe false
            val report = panel.backend.reportDirectory(started.runId).shouldNotBeNull()
            withContext(Dispatchers.IO) { Files.isRegularFile(report.resolve("index.html")) } shouldBe true
            panel.backend.reportDirectory(RunId("run_unknown")).shouldBeNull()
            withContext(Dispatchers.IO) {
                Files.list(panel.config.evidenceDir.resolve("panel-runs")).use { it.count() }
            } shouldBe 0L
        }

    @Test
    fun `a draft, an unknown scenario or an impossible tester count does not run`() =
        runBlocking<Unit> {
            val panel = harness()
            val draft =
                panel.panel.container.scenarioCatalog.createDraft(
                    tinyCampaign(step = "Open the home page"),
                    az.petek.scenarios.domain.ScenarioSource.USER,
                    ScenarioVersionId(panel.approved()),
                )

            shouldThrow<PanelConflictException> { panel.backend.startRun(RunRequest(scenarioId = draft.id.value)) }.message shouldContain
                "təsdiqlənməyib"
            shouldThrow<PanelNotFoundException> { panel.backend.startRun(RunRequest(scenarioId = "scn_unknown")) }
            shouldThrow<PanelRequestException> { panel.backend.startRun(RunRequest(scenarioId = null)) }
            val tooFew =
                shouldThrow<PanelRequestException> { panel.backend.startRun(RunRequest(scenarioId = panel.approved(), testers = 1)) }
            tooFew.problems.single().field shouldBe PanelInstructions.TESTERS
            panel.backend.runs() shouldBe emptyList()
        }

    @Test
    fun `only one run goes at a time and a running run can be stopped, still ending with a report`() =
        runBlocking<Unit> {
            val llm = PanelLlm().apply { agentGate = CompletableDeferred() }
            val panel = harness(llm = llm)
            val scenario = panel.approved()

            val started = panel.backend.startRun(RunRequest(scenarioId = scenario))
            shouldThrow<PanelConflictException> { panel.backend.startRun(RunRequest(scenarioId = scenario)) }.message shouldContain
                "Artıq bir run"

            panel.backend.cancelRun() shouldBe true
            val board = panel.ended(started.runId)

            board.run.outcome shouldBe RunOutcome.ABORTED
            panel.backend.cancelRun() shouldBe false
            panel.backend
                .runs()
                .single()
                .result shouldBe RunResult.ABORTED
        }

    @Test
    fun `closing the panel stops a running run and still tears it down and writes its report first`() =
        runBlocking<Unit> {
            val llm = PanelLlm().apply { agentGate = CompletableDeferred() }
            val panel = harness(llm = llm)
            val started = panel.backend.startRun(RunRequest(scenarioId = panel.approved()))
            eventually(10.seconds) { llm.client.requests.isNotEmpty() shouldBe true }

            open.remove(panel)
            panel.close()

            val reopened = harness()
            val history = reopened.backend.runs().single()
            history.runId shouldBe started.runId
            history.result shouldBe RunResult.ABORTED
            history.reportAvailable shouldBe true
        }

    @Test
    fun `a run against another address runs there, as if it were PETEK_TARGET, and a production address is refused`() =
        runBlocking<Unit> {
            val runs = FakeBrowserEngine()
            val panel = harness(runs = runs)
            val scenario = panel.approved()

            val started = panel.backend.startRun(RunRequest(scenarioId = scenario, target = "http://127.0.0.2:9/app"))
            panel.ended(started.runId)

            panel.backend
                .runs()
                .single()
                .target shouldBe "http://127.0.0.2:9/app"
            runs.options.map { it.baseUrl.toString() }.distinct() shouldBe listOf("http://127.0.0.2:9/app")
            val refused =
                shouldThrow<PanelRequestException> {
                    panel.backend.startRun(
                        RunRequest(scenarioId = scenario, target = "https://kadrohr.com"),
                    )
                }
            refused.problems.single().field shouldBe PanelInstructions.TARGET

            val again = panel.backend.startRun(RunRequest(scenarioId = scenario, target = panel.site.base.toString()))
            panel.ended(again.runId)
            panel.backend
                .runs()
                .first()
                .target shouldBe "http://127.0.0.1:9"
        }

    @Test
    fun `triage of a finished run classifies its surprises and drafts the proposed change for review`() =
        runBlocking<Unit> {
            val llm = PanelLlm(failingSteps = setOf("look"))
            val panel = harness(llm = llm)
            val scenario = panel.approved()
            val started = panel.backend.startRun(RunRequest(scenarioId = scenario))
            panel.ended(started.runId)
            panel.backend.triage(started.runId).shouldBeNull()

            val triage = panel.backend.runTriage(started.runId)

            triage.scenarioId shouldBe scenario
            val verdict = triage.verdicts.single()
            verdict.scenarioStep shouldBe "look"
            verdict.agentId?.value shouldBe "a02"
            verdict.category shouldBe TriageCategory.SCENARIO_BUG
            verdict.rationale shouldBe "Ssenari addımı səhv yazılıb"
            verdict.proposedChange shouldBe "Addımın mətnini dəqiqləşdir"
            val proposal = verdict.proposalScenarioId.shouldNotBeNull()
            val drafted = panel.backend.scenarios().single { it.id == proposal }
            drafted.source shouldBe ScenarioSource.TRIAGE
            drafted.status shouldBe ScenarioStatus.DRAFT
            drafted.parentId shouldBe scenario
            panel.backend
                .scenario(proposal)
                .shouldNotBeNull()
                .yaml shouldContain "Open the home page and read the title"
            panel.backend
                .runs()
                .single()
                .triaged shouldBe true
            panel.backend.triage(started.runId) shouldBe triage
            shouldThrow<PanelNotFoundException> { panel.backend.runTriage(RunId("run_unknown")) }
        }

    @Test
    fun `triage of a run whose text is not in the catalog says why`() =
        runBlocking<Unit> {
            val panel = harness()
            val container = panel.panel.container
            val file = dir.resolve("outside.yaml").also { Files.writeString(it, tinyCampaign(name = "outside")) }
            val campaign = withContext(Dispatchers.IO) { container.campaigns.execute(file, container.knownRunFunctions) }
            val summary = container.campaignRunner().run(campaign, RunOptions())

            shouldThrow<PanelConflictException> { panel.backend.runTriage(summary.runId) }.message shouldContain "kataloqdakı"
        }

    @Test
    fun `the stability of a repeat group is computed from its runs`() =
        runBlocking<Unit> {
            val panel = harness()
            val container = panel.panel.container
            val campaign =
                withContext(Dispatchers.IO) {
                    container.campaigns.execute(dir.resolve("scenarios").resolve("tiny.yaml"), container.knownRunFunctions)
                }
            val summaries = container.repeatRunner(container.campaignRunner()).repeat(campaign, 2)
            val group =
                panel.backend
                    .runs()
                    .first()
                    .repeatGroup
                    .shouldNotBeNull()

            val stability = panel.backend.stability(group).shouldNotBeNull()

            stability.runs shouldContainExactly summaries.map { it.runId }
            stability.steps.map { Triple(it.scenarioStep, it.runs, it.passed) } shouldContainExactly
                listOf(Triple("signup", 2, 2), Triple("look", 2, 2))
            panel.backend.stability("grp_unknown").shouldBeNull()
        }
}
