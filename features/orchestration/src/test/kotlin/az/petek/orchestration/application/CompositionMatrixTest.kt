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
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The composition matrix of Faza 24.14: every condition a run can be in (waves, the account swap, a setup failure, an
 * emitter failure, a race), crossed with every feature of a scenario (`emits`/`wait_for`, `{last_id}`,
 * `only_one_succeeds`, a forbidden step), has a test. The cells tested elsewhere:
 *
 * - waves × `emits`/`wait_for`: RunnerWavesTest, "every wave's readers get their own post" and "not covered";
 * - waves × `only_one_succeeds`: RunnerWavesTest, "a race with more racers than a wave holds is split"; WavesTest;
 * - swap × `emits`/`wait_for`: RunnerSwapTest, "a waiter never takes the event the first pass published";
 * - setup failure × `only_one_succeeds`: RunnerRaceTest, "a race left with one manager after the other failed setup";
 * - emitter failure × `emits`/`wait_for`: RunnerEventsTest, "a waiter that times out fails its wait"; RunnerDeliveryTest;
 * - race × `emits`/`wait_for`: RunnerRaceTest, "only the winner emits the step's event";
 * - race × `only_one_succeeds`: RunnerRaceTest and RunnerConcurrencyTest throughout;
 * - race × forbidden step: RunnerRaceTest, "a forbidden approval is a permission refusal, not a lost race".
 *
 * The other twelve cells are the nested classes below, one class per condition, one test per feature.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositionMatrixTest {
    private val ok = ActionOutcome(ActionStatus.SUCCEEDED, "ok")
    private val approve = RequestPattern("POST", ".*/approve")
    private val forbiddenPath = "/api/tickets/t1/approve"

    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    /** The admin posts a note (a new object each time, `n1`, `n2`, ...), the employees open it by `{last_id}`. */
    private fun RunnerFixture.posting() {
        val notes = AtomicInteger()
        agents.script = { call, _ ->
            if (call.scenarioStep.startsWith("post")) {
                ActionOutcome(ActionStatus.SUCCEEDED, "posted", objectId = "n${notes.incrementAndGet()}")
            } else {
                ok
            }
        }
    }

    private fun postAndRead(
        managers: Int = 0,
        employees: Int = 2,
        waveSize: Int? = null,
    ) = campaign(
        managers = managers,
        employees = employees,
        steps =
            listOf(
                step("post", admin(), emits = "note_posted"),
                step("read", employees(), StepAction.Do("Open note {last_id}"), waitFor = "note_posted", waitTimeout = 5.seconds),
            ),
    ).let { if (waveSize == null) it else it.copy(settings = it.settings.copy(waveSize = waveSize)) }

    /** The employees try an approval the site must refuse (403); [accepted] says whose page the site let through. */
    private fun RunnerFixture.tryingForbidden(accepted: (AgentCallKey) -> Boolean) {
        agents.script = { call, _ ->
            if (call.scenarioStep.startsWith("forbidden")) {
                val status = if (accepted(AgentCallKey(call.scenarioStep, call.agentId.value))) 200 else 403
                browser.session(call.agentId.value).mutated("POST", forbiddenPath, status)
            }
            ok
        }
    }

    private data class AgentCallKey(
        val scenarioStep: String,
        val agent: String,
    )

    private val forbidden =
        step(
            "forbidden",
            employees(),
            StepAction.Do("Try to approve the ticket"),
            assertions = listOf(AssertionSpec.HttpStatus(forbiddenPath, "POST", 403)),
        )

    private fun RunnerFixture.rendered(scenarioStep: String): Map<String, String> =
        agents.callsFor(scenarioStep).associate { it.agentId.value to (it.action as StepAction.Do).instruction }

    private fun RunnerFixture.forbiddenAccepted(): Set<Pair<String, String?>> =
        evidence.stepList
            .filter { it.kind == StepKind.DO && it.detail?.startsWith("forbidden_accepted") == true }
            .map { it.scenarioStep to it.agentId?.value }
            .toSet()

    @Nested
    inner class Waves {
        @Test
        fun `in waves every reader's last_id is the object of its own wave`() =
            runTest {
                val f = fixture()
                f.posting()

                f.runner().run(postAndRead(employees = 4, waveSize = 2))

                // The admin is in every wave and posts in each: a02, a04 (wave 1) open n1, a03, a05 (wave 2) open n2.
                f.rendered("read") shouldBe
                    mapOf("a02" to "Open note n1", "a04" to "Open note n1", "a03" to "Open note n2", "a05" to "Open note n2")
            }

        @Test
        fun `in waves a forbidden action is judged by the requests of each wave`() =
            runTest {
                val f = fixture()
                // Only in the second wave does the site let a03's page approve.
                f.tryingForbidden { it.agent == "a03" }
                val base = campaign(managers = 0, employees = 4, steps = listOf(forbidden))

                val summary = f.runner().run(base.copy(settings = base.settings.copy(waveSize = 2)))

                f.forbiddenAccepted() shouldBe setOf("forbidden" to "a03")
                summary.outcome shouldBe RunOutcome.FAILED
            }
    }

    @Nested
    inner class Swap {
        private val swap = RunOptions(swapAccounts = true)

        @Test
        fun `in the swap the readers open the swap's object, never the first pass's`() =
            runTest {
                val f = fixture()
                f.posting()

                f.runner().run(postAndRead(), swap)

                // The swap posts n2: its readers open that one, never the first pass's n1.
                f.rendered("read").values.toSet() shouldBe setOf("Open note n1")
                f.rendered("read@swap").values.toSet() shouldBe setOf("Open note n2")
            }

        @Test
        fun `in the swap the race runs again and has its own winner`() =
            runTest {
                val f = fixture()
                // a02 wins the first pass, a03 the swap: each pass is its own race with its own winner.
                f.agents.script = { call, _ ->
                    if (call.scenarioStep.startsWith("race")) {
                        val winner = if (call.scenarioStep == "race") "a02" else "a03"
                        val status = if (call.agentId.value == winner) 303 else 409
                        f.browser.session(call.agentId.value).mutated("POST", "/tickets/t1/approve", status)
                    }
                    ok
                }
                val racing =
                    campaign(
                        managers = 2,
                        employees = 0,
                        steps =
                            listOf(
                                step("race", managers(), parallel = true, assertions = listOf(AssertionSpec.OnlyOneSucceeds(approve))),
                            ),
                    )

                val summary = f.runner().run(racing, swap)

                f.evidence.assertionList
                    .filter { it.type == "only_one_succeeds" }
                    .map { it.scenarioStep to it.verdict } shouldContainExactly
                    listOf("race" to Verdict.PASSED, "race@swap" to Verdict.PASSED)
                f.verify.groupCalls.map { results -> results.single { it.succeeded }.agentId.value } shouldContainExactly
                    listOf("a02", "a03")
                summary.outcome shouldBe RunOutcome.PASSED
            }

        @Test
        fun `in the swap a forbidden action the site lets through is caught for that account`() =
            runTest {
                val f = fixture()
                // The site refuses a02 in the first pass and lets the same account through in the swap.
                f.tryingForbidden { it.scenarioStep == "forbidden@swap" && it.agent == "a02" }

                f.runner().run(campaign(managers = 0, employees = 2, steps = listOf(forbidden)), swap)

                f.forbiddenAccepted() shouldBe setOf("forbidden@swap" to "a02")
            }
    }

    @Nested
    inner class SetupFailure {
        /** a02 (the only IT employee) fails to join; a03 is the HR employee. */
        private fun RunnerFixture.a02FailsToJoin(then: suspend (String) -> ActionOutcome = { ok }) {
            agents.script = { call, _ ->
                when {
                    call.scenarioStep == "join" && call.agentId == AgentId("a02") -> {
                        ActionOutcome(ActionStatus.FAILED, "no e-mail", failureReason = FailureReason.MAIL_TIMEOUT)
                    }

                    call.scenarioStep == "post" -> {
                        ActionOutcome(ActionStatus.SUCCEEDED, "posted", objectId = "n1")
                    }

                    else -> {
                        then(call.scenarioStep)
                    }
                }
            }
        }

        @Test
        fun `a setup receiver whose event no tester of the run can emit is left out, never counted as set up`() =
            runTest {
                val f = fixture()
                // Nobody manages, so nobody sends the invitations the employees' join waits for.
                val inviting =
                    campaign(
                        managers = 0,
                        employees = 2,
                        setup =
                            listOf(
                                step("invite", managers(), phase = StepPhase.SETUP, emits = "invite_sent"),
                                step(
                                    "join",
                                    employees(),
                                    StepAction.Run("register_and_login"),
                                    phase = StepPhase.SETUP,
                                    waitFor = "invite_sent",
                                ),
                            ),
                        steps = listOf(step("look", employees())),
                    )

                val summary = f.runner().run(inviting)

                f.step("join", StepKind.WAIT, "a02").detail!! shouldStartWith
                    "emitter_absent: step 'invite', which emits invite_sent, had no tester here (none of its testers is in the run)"
                listOf("a02", "a03").forEach { agent ->
                    f.identities.statusReasons[summary.runId to AgentId(agent)] shouldBe "emitter_absent"
                }
                // Out of the later steps: they never joined, so they never act as if they had.
                f.evidence.stepList.none { it.scenarioStep == "look" && it.action.startsWith("do") } shouldBe true
            }

        @Test
        fun `an emitter that failed setup leaves its receivers skipped and the step not covered`() =
            runTest {
                val f = fixture()
                f.a02FailsToJoin()
                val ticketing =
                    campaign(
                        managers = 0,
                        employees = 2,
                        setup = listOf(setupStep("join", employees())),
                        steps =
                            listOf(
                                step("ticket", employees("IT"), emits = "ticket_created"),
                                step("answer", employees("HR"), waitFor = "ticket_created", waitTimeout = 5.seconds),
                            ),
                    )

                val summary = f.runner().run(ticketing)

                // The only emitter is out: its receiver is skipped at once, and nobody could check the step.
                f.step("answer", StepKind.WAIT, "a03").let {
                    it.status shouldBe StepStatus.SKIPPED
                    it.detail!! shouldStartWith "emitter_absent: step 'ticket', which emits ticket_created, had no tester here"
                }
                f.evidence.stepList
                    .single { it.action == "coverage" }
                    .detail!! shouldStartWith "not_covered:"
                summary.outcome shouldBe RunOutcome.FAILED
            }

        @Test
        fun `a reader that failed setup is skipped, the others open the object`() =
            runTest {
                val f = fixture()
                f.a02FailsToJoin()

                f.runner().run(postAndRead().copy(setup = listOf(setupStep("join", employees()))))

                // The one who joined opens the admin's note; the one who did not is skipped, never rendered.
                f.rendered("read") shouldBe mapOf("a03" to "Open note n1")
                f.evidence.stepList
                    .single { it.scenarioStep == "read" && it.agentId == AgentId("a02") }
                    .detail!! shouldContain "agent failed earlier"
                f.evidence.stepList.none { it.detail.orEmpty().startsWith("template_error") } shouldBe true
            }

        @Test
        fun `a forbidden step whose tester failed setup judges nothing`() =
            runTest {
                val f = fixture()
                f.a02FailsToJoin()
                val itOnly = forbidden.copy(actors = employees("IT"))

                f.runner().run(
                    campaign(managers = 0, employees = 2, setup = listOf(setupStep("join", employees())), steps = listOf(itOnly)),
                )

                // Nobody left to try: nothing is judged, neither refused nor accepted.
                f.agents.callsFor("forbidden").shouldBeEmpty()
                f.evidence.assertionList
                    .filter { it.scenarioStep == "forbidden" }
                    .shouldBeEmpty()
                f.forbiddenAccepted().shouldBeEmpty()
            }
    }

    @Nested
    inner class EmitterFailure {
        private fun RunnerFixture.emitterFails(step: String) {
            agents.script = { call, _ ->
                if (call.scenarioStep == step) ActionOutcome(ActionStatus.FAILED, "the form was refused") else ok
            }
        }

        @Test
        fun `without the event no reader renders last_id`() =
            runTest {
                val f = fixture()
                f.emitterFails("post")

                f.runner().run(postAndRead())

                // Nothing to open: the readers' actions are skipped, so `{last_id}` is never rendered, let alone guessed.
                f.agents.callsFor("read").shouldBeEmpty()
                f.evidence.stepList
                    .filter { it.scenarioStep == "read" && it.kind == StepKind.DO }
                    .map { it.status to it.detail } shouldContainExactly
                    List(2) { StepStatus.SKIPPED to "not evaluated: note_posted not received" }
                f.evidence.stepList.none { it.detail.orEmpty().startsWith("template_error") } shouldBe true
            }

        @Test
        fun `a race whose event never came is undecided`() =
            runTest {
                val f = fixture()
                f.emitterFails("ticket")
                val racing =
                    campaign(
                        steps =
                            listOf(
                                step("ticket", employees("IT", nth = 1), emits = "ticket_created"),
                                step(
                                    "race",
                                    managers(),
                                    waitFor = "ticket_created",
                                    waitTimeout = 5.seconds,
                                    parallel = true,
                                    assertions = listOf(AssertionSpec.OnlyOneSucceeds(approve)),
                                ),
                            ),
                    )

                f.runner().run(racing)

                // No ticket, no race: undecided, not a race the site lost.
                f.agents.callsFor("race").shouldBeEmpty()
                f.evidence.assertionList.single { it.type == "only_one_succeeds" }.let {
                    it.verdict shouldBe Verdict.INCONCLUSIVE
                    it.note shouldBe "a race needs at least 2 racing actors; none raced"
                }
            }

        @Test
        fun `a forbidden step on an object never created is a gap of the test, not the site's`() =
            runTest {
                val f = fixture()
                f.emitterFails("ticket")
                val onTheTicket =
                    forbidden.copy(
                        actors = employees("HR"),
                        action = StepAction.Do("Try to approve ticket {event.ticket_created.id}"),
                        assertions = listOf(AssertionSpec.HttpStatus("/api/tickets/{event.ticket_created.id}/approve", "POST", 403)),
                    )

                f.runner().run(
                    campaign(
                        managers = 0,
                        employees = 2,
                        steps = listOf(step("ticket", employees("IT"), emits = "ticket_created"), onTheTicket),
                    ),
                )

                // The ticket never existed: the step cannot even be written down, a gap of the test, never the site's.
                f.step("forbidden", StepKind.DO, "a03").let {
                    it.status shouldBe StepStatus.FAILED
                    it.detail!! shouldStartWith "template_error: "
                }
                f.evidence.assertionList
                    .filter { it.scenarioStep == "forbidden" }
                    .map { it.verdict }
                    .toSet() shouldBe setOf(Verdict.SKIPPED)
                f.forbiddenAccepted().shouldBeEmpty()
            }
    }

    @Nested
    inner class Race {
        @Test
        fun `the winner's object is last_id for the group check and every reader`() =
            runTest {
                val f = fixture()
                f.agents.script = { call, _ ->
                    when (call.scenarioStep) {
                        "race" -> {
                            val winner = call.agentId == AgentId("a02")
                            f.browser.session(call.agentId.value).mutated("POST", "/tickets/t1/approve", if (winner) 303 else 409)
                            ActionOutcome(ActionStatus.SUCCEEDED, "approved", objectId = "decided-by-${call.agentId.value}")
                        }

                        else -> {
                            ok
                        }
                    }
                }
                val racing =
                    campaign(
                        managers = 2,
                        employees = 2,
                        steps =
                            listOf(
                                step(
                                    "race",
                                    managers(),
                                    parallel = true,
                                    emits = "ticket_decided",
                                    assertions = listOf(AssertionSpec.OnlyOneSucceeds(approve)),
                                ),
                                step("review", employees(), StepAction.Do("Review ticket {last_id}"), waitFor = "ticket_decided"),
                            ),
                    )

                f.runner().run(racing)

                // Only the winner emits: the group check and every reader see its object, never the loser's.
                f.evidence.eventList.map { it.objectId } shouldContainExactly listOf("decided-by-a02")
                f.verify.groupInputs
                    .single()
                    .templates.lastId shouldBe "decided-by-a02"
                f.rendered("review").values.toSet() shouldBe setOf("Review ticket decided-by-a02")
            }

        @Test
        fun `a race nobody won leaves no object to the group check and none to the readers`() =
            runTest {
                val f = fixture()
                f.agents.script = { call, _ ->
                    when (call.scenarioStep) {
                        "race" -> {
                            // The site refused both: no winner, so no racer's object is the step's own.
                            f.browser.session(call.agentId.value).mutated("POST", "/tickets/t1/approve", 409)
                            ActionOutcome(ActionStatus.SUCCEEDED, "approved", objectId = "decided-by-${call.agentId.value}")
                        }

                        else -> {
                            ok
                        }
                    }
                }
                val racing =
                    campaign(
                        managers = 2,
                        employees = 2,
                        steps =
                            listOf(
                                step(
                                    "race",
                                    managers(),
                                    parallel = true,
                                    emits = "ticket_decided",
                                    assertions = listOf(AssertionSpec.OnlyOneSucceeds(approve)),
                                ),
                            ),
                    )

                f.runner().run(racing)

                f.evidence.eventList.shouldBeEmpty()
                f.verify.groupInputs
                    .single()
                    .templates.lastId
                    .shouldBeNull()
                f.evidence.assertionList
                    .single { it.type == "only_one_succeeds" }
                    .verdict shouldBe Verdict.FAILED
            }
    }
}
