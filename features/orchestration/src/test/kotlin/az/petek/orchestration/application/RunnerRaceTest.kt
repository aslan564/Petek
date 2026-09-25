package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.browser.domain.BrowserActionException
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.RequestPattern
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.TaskState
import az.petek.orchestration.testing.AgentCall
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `only_one_succeeds` decided by what each manager's own browser sent and the target answered (CLAUDE.md rule 2),
 * never by the agents' `done(success)`; the manager that loses a race did what a race expects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerRaceTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    private val approve = RequestPattern("POST", ".*/approve")

    /** a05 (employee IT) creates the ticket; a02 (manager IT) and a03 (manager HR) race to approve it. */
    private fun race(
        spec: AssertionSpec.OnlyOneSucceeds = AssertionSpec.OnlyOneSucceeds(approve),
        emits: String? = null,
    ) = campaign(
        steps =
            listOf(
                step("ticket", employees("IT", nth = 1), emits = "ticket_created"),
                step("race", managers(), waitFor = "ticket_created", parallel = true, emits = emits, assertions = listOf(spec)),
            ),
    )

    /** In the race, [requests] are what each manager's page sends (path and status); [outcomes] what its agent says. */
    private fun RunnerFixture.racing(
        requests: Map<String, List<Pair<String, Int>>>,
        outcomes: Map<String, ActionOutcome> = emptyMap(),
        before: suspend (AgentCall) -> Unit = {},
    ) {
        agents.script = { call, _ ->
            before(call)
            if (call.scenarioStep == "race") {
                val session = browser.session(call.agentId.value)
                requests[call.agentId.value].orEmpty().forEach { (path, status) -> session.mutated("POST", path, status) }
                outcomes[call.agentId.value] ?: DONE
            } else {
                DONE
            }
        }
    }

    private fun RunnerFixture.actionOf(agent: String) = step("race", StepKind.DO, agent)

    // --- the two runs that found the bug ------------------------------------------------------------------------------

    @Test
    fun `run 1 - both agents claim success but only the first approval was accepted`() =
        runTest {
            val f = fixture()
            f.racing(
                requests = mapOf("a02" to listOf(TICKET to 303), "a03" to listOf(TICKET to 409)),
                outcomes = mapOf("a03" to ActionOutcome(ActionStatus.SUCCEEDED, "Ticket approved")),
            )

            val summary = f.runner().run(race())

            val group = f.evidence.assertionList.single { it.type == "only_one_succeeds" }
            group.verdict shouldBe Verdict.PASSED
            f.verify.groupCalls
                .single()
                .map { it.agentId.value to it.succeeded } shouldBe listOf("a02" to true, "a03" to false)
            f.actionOf("a02").status shouldBe StepStatus.PASSED
            val loser = f.actionOf("a03")
            loser.status shouldBe StepStatus.PASSED
            loser.detail shouldBe "lost_race: POST /tickets/t1/approve -> 409; won by a02; agent: Ticket approved"
            summary.outcome shouldBe RunOutcome.PASSED
            summary.stepsFailed shouldBe 0
            summary.assertionsFailed shouldBe 0
            summary.failedAgents shouldBe 0
        }

    @Test
    fun `run 2 - the loser reports the ticket as already decided and nothing counts as failed`() =
        runTest {
            val f = fixture()
            val decided =
                ActionOutcome(ActionStatus.FAILED, "Bu müraciət artıq qərarlaşdırılıb", failureReason = FailureReason.PROBLEM_REPORTED)
            f.racing(requests = mapOf("a02" to listOf(TICKET to 303)), outcomes = mapOf("a03" to decided))

            val summary = f.runner().run(race())

            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.PASSED
            val loser = f.actionOf("a03")
            loser.status shouldBe StepStatus.PASSED
            loser.detail shouldBe "lost_race: no matching request; won by a02; agent: Bu müraciət artıq qərarlaşdırılıb"
            f.verify.groupCalls
                .single()
                .last()
                .lostRace shouldBe true
            summary.outcome shouldBe RunOutcome.PASSED
            summary.stepsFailed shouldBe 0
            summary.failedAgents shouldBe 0
            f.monitor.statesOf("race", "a03").last() shouldBe TaskState.LOST_RACE
            f.monitor.statesOf("race", "a02").last() shouldBe TaskState.PASSED
        }

    // --- verdicts --------------------------------------------------------------------------------------------------------

    @Test
    fun `two accepted approvals fail the race and nobody lost`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a02" to listOf(TICKET to 303), "a03" to listOf(TICKET to 303)))

            val summary = f.runner().run(race())

            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.FAILED
            f.actionOf("a03").detail shouldBe "ok; request: POST /tickets/t1/approve -> 303"
            f.verify.groupCalls
                .single()
                .none { it.lostRace } shouldBe true
            summary.outcome shouldBe RunOutcome.FAILED
            summary.assertionsFailed shouldBe 1
        }

    @Test
    fun `agents that claim success without an accepted request make nobody the winner`() =
        runTest {
            val f = fixture()
            f.racing(requests = emptyMap())

            val summary = f.runner().run(race())

            f.verify.groupCalls
                .single()
                .map { it.succeeded } shouldBe listOf(false, false)
            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.FAILED
            f.actionOf("a02").detail shouldBe "ok; request: no matching request"
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `a refusal as already decided is a lost race even when nobody else won in this step`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a02" to listOf(TICKET to 409), "a03" to listOf(TICKET to 422)))

            val summary = f.runner().run(race())

            f.actionOf("a02").detail!! shouldStartWith "lost_race: POST /tickets/t1/approve -> 409; agent:"
            f.actionOf("a03").detail!! shouldStartWith "lost_race: POST /tickets/t1/approve -> 422"
            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.FAILED
            summary.failedAgents shouldBe 0
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `requests that do not match the pattern do not decide the race`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a02" to listOf(TICKET to 303), "a03" to listOf("/tickets/t1/comment" to 201, TICKET to 409)))

            f.runner().run(race())

            f.verify.groupCalls
                .single()
                .map { it.succeeded } shouldBe listOf(true, false)
            f.verify.groupCalls
                .single()
                .last()
                .race!!
                .requests
                .map { it.path } shouldContainExactly listOf(TICKET)
        }

    @Test
    fun `without a pattern every mutating request counts and a refusal outweighs an unrelated success`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a02" to listOf(TICKET to 303), "a03" to listOf("/notifications/read" to 200, TICKET to 409)))

            f.runner().run(race(AssertionSpec.OnlyOneSucceeds()))

            f.verify.groupCalls
                .single()
                .map { it.succeeded } shouldBe listOf(true, false)
            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `requests sent before the race do not count for it`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a03" to listOf(TICKET to 303))) { call ->
                if (call.scenarioStep == "ticket") {
                    // An approval by a02 in an earlier step, long before the race starts.
                    f.browser.session("a02").mutated("POST", TICKET, 303)
                    delay(5.seconds)
                }
            }

            f.runner().run(race())

            f.verify.groupCalls
                .single()
                .map { it.agentId.value to it.succeeded } shouldBe listOf("a02" to false, "a03" to true)
            f.actionOf("a02").detail shouldBe "lost_race: no matching request; won by a03; agent: ok"
        }

    @Test
    fun `only the winner emits the step's event`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a02" to listOf(TICKET to 409), "a03" to listOf(TICKET to 303)))

            f.runner().run(race(emits = "ticket_approved"))

            f.evidence.eventList
                .filter { it.name == "ticket_approved" }
                .map { it.emitter.value } shouldBe listOf("a03")
            f.steps("race", StepKind.EMIT).map { it.agentId?.value } shouldBe listOf("a03")
        }

    // --- who lost, who failed --------------------------------------------------------------------------------------------

    @Test
    fun `an agent that crashed is a failure, not a lost race`() =
        runTest {
            val f = fixture()
            val crash = ActionOutcome(ActionStatus.ERROR, "page crashed", failureReason = FailureReason.BROWSER_ERROR)
            f.racing(requests = mapOf("a02" to listOf(TICKET to 303)), outcomes = mapOf("a03" to crash))

            val summary = f.runner().run(race())

            val failed = f.actionOf("a03")
            failed.status shouldBe StepStatus.ERROR
            failed.detail shouldBe "browser_error: page crashed; request: no matching request"
            f.evidence.artifactList.map { it.stepId } shouldContainExactly listOf(failed.stepId)
            f.monitor.statesOf("race", "a03").last() shouldBe TaskState.FAILED
            summary.failedAgents shouldBe 1
        }

    @Test
    fun `a forbidden approval is a permission refusal, not a lost race`() =
        runTest {
            val f = fixture()
            val refused = ActionOutcome(ActionStatus.BLOCKED, "no permission", failureReason = FailureReason.PERMISSION_DENIED)
            f.racing(
                requests = mapOf("a02" to listOf(TICKET to 303), "a03" to listOf(TICKET to 403)),
                outcomes = mapOf("a03" to refused),
            )

            val summary = f.runner().run(race())

            val record = f.actionOf("a03")
            record.status shouldBe StepStatus.BLOCKED
            record.detail shouldBe "permission_denied: no permission; request: POST /tickets/t1/approve -> 403"
            f.verify.groupCalls
                .single()
                .last()
                .lostRace shouldBe false
            summary.failedAgents shouldBe 0
        }

    @Test
    fun `a winner whose agent reported a problem is still the winner, and its agent failed`() =
        runTest {
            val f = fixture()
            val confused = ActionOutcome(ActionStatus.FAILED, "button did nothing", failureReason = FailureReason.PROBLEM_REPORTED)
            f.racing(
                requests = mapOf("a02" to listOf(TICKET to 303), "a03" to listOf(TICKET to 409)),
                outcomes = mapOf("a02" to confused),
            )

            val summary = f.runner().run(race())

            f.evidence.assertionList
                .single { it.type == "only_one_succeeds" }
                .verdict shouldBe Verdict.PASSED
            f.actionOf("a02").status shouldBe StepStatus.FAILED
            f.actionOf("a03").detail!! shouldStartWith "lost_race:"
            summary.failedAgents shouldBe 1
        }

    @Test
    fun `an actor whose requests cannot be read neither wins nor loses, and the race fails`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a02" to listOf(TICKET to 303)))
            f.browser.configure = { session ->
                if (session.label == "a03") session.mutationsFailure = BrowserActionException("browser session 'a03' is closed")
            }

            f.runner().run(race())

            val results = f.verify.groupCalls.single()
            results.map { it.succeeded } shouldBe listOf(true, false)
            results.last().lostRace shouldBe false
            results
                .last()
                .race!!
                .unavailable!! shouldContain "BrowserActionException"
            f.actionOf("a03").detail!! shouldContain "request: requests unavailable"
        }

    @Test
    fun `race records keep each actor's own action times`() =
        runTest {
            val f = fixture()
            f.racing(requests = mapOf("a02" to listOf(TICKET to 303), "a03" to listOf(TICKET to 409))) { call ->
                if (call.scenarioStep == "race" && call.agentId == AgentId("a03")) delay(40.seconds)
            }

            f.runner().run(race())

            f.actionOf("a02").durationMs shouldBe 0
            f.actionOf("a03").durationMs shouldBe 40_000
        }

    @Test
    fun `a step without a race keeps the agent's word and records nothing about requests`() =
        runTest {
            val f = fixture()
            f.racing(requests = emptyMap())
            val plain = campaign(steps = listOf(step("work", managers())))

            f.runner().run(plain)

            f.steps("work", StepKind.DO).map { it.detail } shouldBe listOf("ok", "ok")
            f.verify.groupCalls.shouldBeEmpty()
            f.evidence.assertionList.shouldBeEmpty()
            f.steps("work", StepKind.DO).single { it.agentId == AgentId("a02") }.status shouldBe StepStatus.PASSED
            f.monitor.statesOf("work", "a02").last() shouldBe TaskState.PASSED
            f.evidence.eventList
                .singleOrNull()
                .shouldBeNull()
        }

    private companion object {
        const val TICKET = "/tickets/t1/approve"
        val DONE = ActionOutcome(ActionStatus.SUCCEEDED, "ok")
    }
}
