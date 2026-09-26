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
import az.petek.agent.domain.SharedRunState
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.StepAction
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.oracle.domain.TestCompany
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.everyoneButAdmin
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Proof that testers cannot reach into each other's environment, at the size of a real campaign and far beyond
 * (docs/requirements/R01, R02, R12). The real orchestrator, step executor, event bus, actor resolver and shared state
 * run N agents through a portal-shaped campaign; only the browser, the agents' decisions and the target are fakes, so
 * what is checked is the harness's own behaviour:
 *
 * - every agent gets its own identity, session and runtime; no two share a session object;
 * - an agent's runtime knows its colleagues without their secrets (the roster carries no password);
 * - the values agents share (`company_code`) are write-once: an agent that tries to change them is refused;
 * - every step, assertion, event and receipt is attributed to the agent that acted, by the harness;
 * - `{last_id}` is an agent's own object, the object it waited for, or the state before the step began — never an id
 *   a colleague produced concurrently in the same step;
 * - one emitted event reaches every receiver exactly once.
 */
class TesterIsolationAtScaleTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    /** What each agent saw from inside its own runtime while acting. */
    private data class SelfView(
        val calledAs: AgentId,
        val identity: AgentId,
        val sessionLabel: String,
        val email: String,
        val rosterSize: Int,
        val couldChangeCompanyCode: Boolean,
    )

    private fun bigCampaign(
        managers: Int,
        employees: Int,
    ) = campaign(
        setup =
            listOf(
                setupStep("owner_signup", admin(), StepAction.Do("Sign up and create the company")),
                setupStep("seed", admin(), StepAction.Run("seed_company")),
                setupStep("join", everyoneButAdmin()),
            ),
        steps =
            listOf(
                step("announce", admin(), StepAction.Do("Publish the announcement"), emits = "announcement_created"),
                step(
                    "read",
                    everyoneButAdmin(),
                    waitFor = "announcement_created",
                    assertions = listOf(AssertionSpec.VisibleText(ANNOUNCEMENT, 5.seconds), AssertionSpec.LatencyMax(5.seconds)),
                ),
                step(
                    "ticket",
                    employees(),
                    StepAction.Do("Create a ticket"),
                    emits = "ticket_created",
                    assertions = listOf(AssertionSpec.Oracle("/test/tickets/{last_id}", "status", "open", null)),
                ),
                step(
                    "check",
                    employees(),
                    StepAction.Do("Look at the newest ticket"),
                    assertions = listOf(AssertionSpec.Oracle("/test/tickets/{last_id}", "status", "open", null)),
                ),
                step("whoami", everyoneButAdmin(), StepAction.Do("Say who you are")),
            ),
        managers = managers,
        employees = employees,
        departments = (1..10).map { "D$it" },
        maxMinutes = 600,
    )

    @ParameterizedTest(name = "{0} testers")
    @MethodSource("sizes")
    fun `every tester acts only as itself and the harness keeps them apart`(testers: Int) =
        runTest(timeout = 10.minutes) {
            val managers = testers / 10
            val employees = testers - 1 - managers
            val f = fixture()
            f.oracle.companies["c1"] = TestCompany("c1", "Pətək Test MMC", "PTK-1", isTest = true)
            f.browser.configure = { session -> session.visibleTexts += ANNOUNCEMENT }
            val views = ConcurrentHashMap<AgentId, SelfView>()
            val failingEmployee = AgentId.of(testers) // the last employee's ticket action fails: it emits nothing
            f.agents.script = { call, runtime ->
                when (call.scenarioStep) {
                    "seed" -> {
                        runtime.shared.put(SharedRunState.COMPANY_ID, "c1")
                        runtime.shared.put(SharedRunState.COMPANY_CODE, ADMIN_CODE)
                        ActionOutcome(ActionStatus.SUCCEEDED, "seeded", objectId = "c1")
                    }

                    "announce" -> {
                        ActionOutcome(ActionStatus.SUCCEEDED, "announced", objectId = "ann-1")
                    }

                    "ticket" -> {
                        if (call.agentId == failingEmployee) {
                            ActionOutcome(ActionStatus.FAILED, "the form was refused", failureReason = FailureReason.PROBLEM_REPORTED)
                        } else {
                            ActionOutcome(ActionStatus.SUCCEEDED, "ticket created", objectId = "t-${call.agentId}")
                        }
                    }

                    "whoami" -> {
                        views[call.agentId] =
                            SelfView(
                                calledAs = call.agentId,
                                identity = runtime.identity.agentId,
                                sessionLabel = runtime.session.label,
                                email = runtime.identity.email,
                                rosterSize = runtime.roster.size,
                                couldChangeCompanyCode = runtime.shared.put(SharedRunState.COMPANY_CODE, "CODE-${call.agentId}"),
                            )
                        ActionOutcome(ActionStatus.SUCCEEDED, "I am ${runtime.identity.agentId}")
                    }

                    else -> {
                        ActionOutcome(ActionStatus.SUCCEEDED, "ok")
                    }
                }
            }

            val summary = f.runner().run(bigCampaign(managers, employees))

            val everyone = (1..testers).map { AgentId.of(it) }
            val nonAdmins = everyone.drop(1)
            summary.outcome shouldBe RunOutcome.FAILED // exactly the one scripted ticket failure
            summary.failedAgents shouldBe 1

            // One identity, one session, one runtime per tester; sessions are distinct objects labelled by agent id.
            f.browser.opened.map { it.label } shouldContainExactlyInAnyOrder everyone.map { it.value }
            f.browser.sessions.values
                .map { System.identityHashCode(it) }
                .toSet() shouldHaveSize testers
            val runtimes = f.agents.runtimes
            runtimes.keys shouldContainExactlyInAnyOrder everyone
            runtimes.values.all { it.session.label == it.identity.agentId.value } shouldBe true
            runtimes.values.map { it.identity.email }.toSet() shouldHaveSize testers
            runtimes.values
                .map { it.identity.password.reveal() }
                .toSet() shouldHaveSize testers
            runtimes.values.map { it.storageStatePath }.toSet() shouldHaveSize testers

            // Every agent acted as itself: the runtime it was given is the identity the harness called it for.
            views.keys shouldContainExactlyInAnyOrder nonAdmins
            views.values.all { it.calledAs == it.identity && it.sessionLabel == it.identity.value } shouldBe true
            views.values.all { it.rosterSize == testers } shouldBe true

            // Shared values are write-once: the admin's company code survived N-1 attempts to change it.
            views.values.none { it.couldChangeCompanyCode } shouldBe true
            f.sharedStates.single().get(SharedRunState.COMPANY_CODE) shouldBe ADMIN_CODE

            // Evidence is attributed by the harness: one record per acting agent, under that agent's id.
            f.steps("whoami", StepKind.DO).map { it.agentId } shouldContainExactlyInAnyOrder nonAdmins
            f.steps("read", StepKind.WAIT).map { it.agentId } shouldContainExactlyInAnyOrder nonAdmins
            f.evidence.eventList
                .filter { it.name == "announcement_created" }
                .map { it.emitter } shouldBe listOf(AgentId.of(1))
            f.evidence.receiptList
                .filter {
                    it.received && it.eventId ==
                        f.evidence.eventList
                            .single { e -> e.name == "announcement_created" }
                            .eventId
                }.map { it.receiver } shouldContainExactlyInAnyOrder nonAdmins
            val employeeIds = nonAdmins.drop(managers)
            f.evidence.eventList
                .filter { it.name == "ticket_created" }
                .map { it.emitter } shouldContainExactlyInAnyOrder employeeIds.filterNot { it == failingEmployee }

            // {last_id}: each employee's own ticket in the emitting step; the refused one falls back to the state before
            // the step (the announcement), never to a colleague's ticket created at the same time.
            val ticketChecks = f.evidence.assertionList.filter { it.scenarioStep == "ticket" && it.type == "oracle" }
            ticketChecks.filter { it.agentId != failingEmployee }.all { it.expected == "/test/tickets/t-${it.agentId}" } shouldBe true
            ticketChecks.filter { it.agentId == failingEmployee }.map { it.expected }.distinct() shouldBe listOf("/test/tickets/ann-1")
            f.step("ticket", StepKind.DO, failingEmployee.value).status shouldBe StepStatus.FAILED

            // A later step without wait_for sees one deterministic value for everyone: the newest ticket before it began.
            val checks =
                f.evidence.assertionList
                    .filter { it.scenarioStep == "check" && it.type == "oracle" }
                    .map { it.expected }
                    .toSet()
            checks shouldHaveSize 1
            (checks.single() in employeeIds.map { "/test/tickets/t-$it" }) shouldBe true

            f.browser.sessions.values
                .filterNot { it.closed }
                .shouldBeEmpty()
        }

    companion object {
        private const val ANNOUNCEMENT = "Sabah 10:00 ümumi iclas"
        private const val ADMIN_CODE = "PTK-1"

        /** 100 and 1 000 on every build; `-Dpetek.isolation.testers=5000` (CI's e2e job) adds the large proof. */
        @JvmStatic
        fun sizes(): List<Int> = listOf(100, 1000) + listOfNotNull(System.getProperty("petek.isolation.testers")?.toIntOrNull())
    }
}
