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
import az.petek.agent.domain.SharedRunState
import az.petek.browser.domain.NetworkObservation
import az.petek.browser.domain.RealtimeTransport
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.StepAction
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunTags
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.identity.domain.IdentityStatus
import az.petek.oracle.domain.TestCompany
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.everyoneButAdmin
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class DefaultCampaignRunnerTest {
    private val announcement = "Sabah 10:00 ümumi iclas"

    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    /** The shape of scenarios/kadrohr.yaml on a 7-tester registry: a01 admin, a02-a03 managers, a04-a07 employees. */
    private fun kadroCampaign() =
        campaign(
            setup =
                listOf(
                    setupStep("owner_signup", admin(), StepAction.Do("Qeydiyyatdan keç və şirkət yarat")),
                    setupStep("seed", admin(), StepAction.Run("seed_company")),
                    setupStep("join", everyoneButAdmin()),
                ),
            steps =
                listOf(
                    step(
                        "announce",
                        admin(),
                        StepAction.Do("Elan yarat: '$announcement'"),
                        emits = "announcement_created",
                        assertions = listOf(AssertionSpec.Oracle("/test/announcements/{last_id}", "status", "published", null)),
                    ),
                    step(
                        "read_announce",
                        employees(),
                        waitFor = "announcement_created",
                        assertions =
                            listOf(
                                AssertionSpec.VisibleText(announcement, 5.seconds),
                                AssertionSpec.LatencyMax(5.seconds),
                                AssertionSpec.Oracle("/test/announcements/{last_id}/receipts", null, null, "{self.email}"),
                            ),
                    ),
                    step("ticket", employees("IT", nth = 1), emits = "ticket_created"),
                    step(
                        "ticket_flow",
                        managers("IT"),
                        waitFor = "ticket_created",
                        assertions = listOf(AssertionSpec.Oracle("/test/tickets/{last_id}", "status", "in_progress", null)),
                    ),
                ),
        )

    private fun RunnerFixture.scriptKadro() {
        oracle.companies["c1"] = TestCompany("c1", "Pətək Test MMC", "PTK-1", isTest = true)
        browser.configure = { session ->
            session.visibleTexts += announcement
            session.observation = NetworkObservation(setOf(RealtimeTransport.SSE, RealtimeTransport.POLLING), listOf("GET /events"))
        }
        agents.script = { call, runtime ->
            when (call.scenarioStep) {
                "seed" -> {
                    runtime.shared.put(SharedRunState.COMPANY_ID, "c1")
                    ActionOutcome(ActionStatus.SUCCEEDED, "seeded", objectId = "c1")
                }

                "announce" -> {
                    ActionOutcome(ActionStatus.SUCCEEDED, "announcement created", objectId = "a1")
                }

                "ticket" -> {
                    ActionOutcome(ActionStatus.SUCCEEDED, "ticket created", objectId = "t1")
                }

                else -> {
                    ActionOutcome(ActionStatus.SUCCEEDED, "ok")
                }
            }
        }
    }

    @Test
    fun `a full run executes setup then steps in order and passes`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            val summary = f.runner().run(kadroCampaign())

            summary.outcome shouldBe RunOutcome.PASSED
            summary.stepsFailed shouldBe 0
            summary.assertionsFailed shouldBe 0
            summary.failedAgents shouldBe 0
            summary.reportDirectory shouldBe Path.of("evidence", summary.runId.value, "report").toString()
            f.agents.calls
                .map { it.scenarioStep }
                .distinct() shouldContainExactly
                listOf("owner_signup", "seed", "join", "announce", "read_announce", "ticket", "ticket_flow")
            f.agents
                .callsFor("join")
                .map { it.agentId.value }
                .sorted() shouldBe listOf("a02", "a03", "a04", "a05", "a06", "a07")
            f.agents
                .callsFor("read_announce")
                .map { it.agentId.value }
                .sorted() shouldBe listOf("a04", "a05", "a06", "a07")
            f.agents.callsFor("ticket").map { it.agentId.value } shouldBe listOf("a04")
            f.agents.callsFor("ticket_flow").map { it.agentId.value } shouldBe listOf("a02")
        }

    @Test
    fun `the run record and the identity registry are persisted for the run`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            val summary = f.runner().run(kadroCampaign())

            val record = f.evidence.runList.single()
            record.runId shouldBe summary.runId
            record.runTag shouldBe RunTags.forRun(summary.runId)
            record.campaignName shouldBe "test-campaign"
            record.campaignHash shouldBe "hash-1"
            record.target shouldBe "https://staging.example.test"
            record.result shouldBe RunResult.PASSED
            record.endedAt.shouldNotBeNull()
            f.generator.specs
                .single()
                .mailDomain shouldBe "test.example.test"
            f.generator.specs
                .single()
                .inviteCount shouldBe 3
            val stored = f.identities.findByRun(summary.runId)
            stored shouldHaveSize 7
            stored.all { it.status == IdentityStatus.ACTIVE } shouldBe true
            stored.first { it.agentId == AgentId("a05") }.storageStatePath shouldBe
                Path.of("build", "storage", summary.runId.value, "a05.json").toString()
        }

    @Test
    fun `every identity gets its own session and agent runtime`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            val summary = f.runner().run(kadroCampaign())

            f.browser.starts.get() shouldBe 1
            f.browser.stops.get() shouldBe 1
            f.browser.opened
                .map { it.label }
                .sorted() shouldBe (1..7).map { AgentId.of(it).value }
            f.browser.opened.all { it.baseUrl.toString() == "https://staging.example.test" } shouldBe true
            f.browser.sessions.values
                .all { it.closed } shouldBe true
            val runtimes = f.agents.runtimes.values
            runtimes shouldHaveSize 7
            runtimes.map { it.shared }.toSet() shouldHaveSize 1
            runtimes.all { it.roster.size == 7 && it.session.label == it.identity.agentId.value } shouldBe true
            f.agents.runtimes
                .getValue(AgentId("a03"))
                .storageStatePath shouldBe Path.of("build", "storage", summary.runId.value, "a03.json")
        }

    @Test
    fun `step records carry kinds, statuses and the correlation of each actor`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            f.runner().run(kadroCampaign())

            f.step("owner_signup", StepKind.DO, "a01").status shouldBe StepStatus.PASSED
            f.step("seed", StepKind.RUN, "a01").action shouldBe "run seed_company"
            f.steps("join", StepKind.RUN) shouldHaveSize 6
            f.steps("read_announce", StepKind.WAIT) shouldHaveSize 4
            val emit = f.step("announce", StepKind.EMIT, "a01")
            emit.status shouldBe StepStatus.PASSED
            emit.detail shouldBe "announcement_created id=a1 (agent_report)"
            val wait = f.step("read_announce", StepKind.WAIT, "a05")
            val action = f.step("read_announce", StepKind.DO, "a05")
            wait.correlationId shouldBe action.correlationId
            f.evidence.stepList
                .map { it.stepId }
                .toSet()
                .size shouldBe f.evidence.stepList.size
        }

    @Test
    fun `emitted events and receipts are recorded with harness times`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            val summary = f.runner().run(kadroCampaign())

            f.evidence.eventList.map { it.name to it.objectId } shouldContainExactly
                listOf("announcement_created" to "a1", "ticket_created" to "t1")
            val event = f.evidence.eventList.first()
            event.emitter shouldBe AgentId("a01")
            event.objectIdSource shouldBe "agent_report"
            event.payloadJson shouldContain "\"id\":\"a1\""
            event.payloadJson shouldContain "\"scenario_step\":\"announce\""
            val receipts = f.evidence.receiptList.filter { it.eventId == event.eventId }
            receipts.map { it.receiver.value }.sorted() shouldBe listOf("a04", "a05", "a06", "a07")
            receipts.all { it.received && it.latencyMs == 0L && it.t1 == event.t0 && it.runId == summary.runId } shouldBe true
        }

    @Test
    fun `last_id and self placeholders reach the assertions`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            f.runner().run(kadroCampaign())

            val oracleChecks = f.evidence.assertionList.filter { it.type == "oracle" }
            oracleChecks.first { it.scenarioStep == "announce" }.expected shouldBe "/test/announcements/a1"
            oracleChecks.filter { it.scenarioStep == "read_announce" }.map { it.expected }.toSet() shouldBe
                setOf("/test/announcements/a1/receipts")
            oracleChecks.first { it.scenarioStep == "ticket_flow" }.expected shouldBe "/test/tickets/t1"
            val receiptCheck = f.verify.actorCalls.first { (specs, input) -> input.scenarioStep == "read_announce" && specs.size == 1 }
            receiptCheck.second.templates.self["email"] shouldBe
                f.agents.runtimes
                    .getValue(receiptCheck.second.agentId!!)
                    .identity.email
            receiptCheck.second.eventEmittedAt shouldBe
                f.buses
                    .single()
                    .latest("announcement_created")
                    ?.t0
        }

    @Test
    fun `do instructions and run arguments are rendered before the agent sees them`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                ActionOutcome(ActionStatus.SUCCEEDED, "ok", objectId = if (call.scenarioStep == "ticket") "t42" else null)
            }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("ticket", employees("IT", nth = 1), emits = "ticket_created"),
                            step(
                                "open",
                                managers("IT"),
                                StepAction.Do("Open ticket {last_id} as {self.name} ({self.role}, {self.department})"),
                            ),
                            step(
                                "approve",
                                managers("HR"),
                                StepAction.Run("approve", mapOf("ticket" to "{event.ticket_created.id}", "by" to "{self.email}")),
                            ),
                        ),
                )

            f.runner().run(campaign)

            val open = f.agents.callsFor("open").single()
            open.action shouldBe StepAction.Do("Open ticket t42 as Tester 2 (manager, IT)")
            open.context.templates.lastId shouldBe "t42"
            open.context.templates.eventIds shouldBe mapOf("ticket_created" to "t42")
            open.context.maxSteps shouldBe 25
            open.context.timeout shouldBe (30 * 60).seconds
            val approve = f.agents.callsFor("approve").single()
            approve.action shouldBe
                StepAction.Run(
                    "approve",
                    mapOf(
                        "ticket" to "t42",
                        "by" to
                            f.agents.runtimes
                                .getValue(AgentId("a03"))
                                .identity.email,
                    ),
                )
            f.step("approve", StepKind.RUN, "a03").action shouldBe
                "run approve (ticket=t42, by=${f.agents.runtimes.getValue(AgentId("a03")).identity.email})"
        }

    @Test
    fun `a template that cannot be rendered fails the step for that actor without calling the agent`() =
        runTest {
            val f = fixture()
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step(
                                "open",
                                admin(),
                                StepAction.Do("Open ticket {last_id}"),
                                assertions = listOf(AssertionSpec.Count("[data-testid=ticket-item]", 1)),
                            ),
                        ),
                )

            val summary = f.runner().run(campaign)

            f.agents.calls.shouldBeEmpty()
            val record = f.step("open", StepKind.DO, "a01")
            record.status shouldBe StepStatus.FAILED
            record.detail!! shouldContain "template_error"
            record.detail!! shouldContain "last_id"
            f.evidence.assertionList
                .single()
                .verdict shouldBe Verdict.SKIPPED
            summary.outcome shouldBe RunOutcome.FAILED
            summary.stepsFailed shouldBe 1
        }

    @Test
    fun `the detected real-time transports are recorded per agent before sessions close`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            f.runner().run(kadroCampaign())

            val observations = f.system(DefaultCampaignRunner.NETWORK_OBSERVATION)
            observations.map { it.agentId?.value } shouldBe (1..7).map { AgentId.of(it).value }
            observations.all { it.detail == "POLLING,SSE" && it.status == StepStatus.PASSED } shouldBe true
        }

    @Test
    fun `the monitor sees the run start, every step, agent states and the finish`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            val summary = f.runner().run(kadroCampaign())

            f.monitor.events.first() shouldBe "runStarted 7"
            f.monitor.events.filter { it.startsWith("step ") } shouldContainExactly
                listOf("owner_signup", "seed", "join", "announce", "read_announce", "ticket", "ticket_flow").map { "step $it" }
            f.monitor.events.last() shouldBe "runFinished PASSED"
            f.monitor.summaries.single() shouldBe summary
            val a05 =
                f.monitor.statuses
                    .filter { it.agentId == AgentId("a05") }
                    .map { it.state }
            a05 shouldContainAll listOf(AgentState.WAITING, AgentState.WORKING, AgentState.IDLE)
            a05.last() shouldBe AgentState.DONE
        }

    @Test
    fun `the company created in setup is registered once and torn down at the end`() =
        runTest {
            val f = fixture().apply { scriptKadro() }

            val summary = f.runner().run(kadroCampaign())

            f.oracle.deleted shouldContainExactly listOf("c1")
            f.evidence.resourceList.shouldBeEmpty()
            f.system("teardown company:c1").single().status shouldBe StepStatus.PASSED
            f.finalizer.calls shouldContainExactly listOf(summary.runId)
        }

    private infix fun <T> List<T>.shouldContainAll(expected: List<T>) {
        expected.filterNot { it in this } shouldBe emptyList()
    }
}
