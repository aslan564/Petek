/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.StepAction
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.identity.domain.IdentityStatus
import az.petek.oracle.domain.TestCompany
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOptions
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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RunnerSetupTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    private val mailTimeout = ActionOutcome(ActionStatus.FAILED, "no e-mail within 60s", failureReason = FailureReason.MAIL_TIMEOUT)

    private fun setup() =
        listOf(
            setupStep("owner_signup", admin(), StepAction.Do("sign up")),
            setupStep("join", everyoneButAdmin()),
        )

    @Test
    fun `an actor that fails setup is marked failed and skipped in every later step`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "join" &&
                    call.agentId == AgentId("a03")
                ) {
                    mailTimeout
                } else {
                    ActionOutcome(ActionStatus.SUCCEEDED, "ok")
                }
            }
            val campaign =
                campaign(
                    setup = setup(),
                    steps = listOf(step("work", everyoneButAdmin()), step("more", employees())),
                )

            val summary = f.runner().run(campaign)

            f.agents.callsFor("work").map { it.agentId.value } shouldNotContain "a03"
            f.agents
                .callsFor("work")
                .map { it.agentId.value }
                .sorted() shouldBe listOf("a02", "a04", "a05", "a06", "a07")
            val skipped = f.steps("work", StepKind.SYSTEM).single()
            skipped.agentId shouldBe AgentId("a03")
            skipped.status shouldBe StepStatus.SKIPPED
            skipped.detail shouldBe "agent failed earlier (mail_timeout)"
            f.steps("more", StepKind.SYSTEM).shouldBeEmpty()
            val stored = f.identities.findByRun(summary.runId).associateBy { it.agentId.value }
            stored.getValue("a03").status shouldBe IdentityStatus.FAILED
            f.identities.statusReasons[summary.runId to AgentId("a03")] shouldBe "mail_timeout"
            stored.filterKeys { it != "a03" }.values.all { it.status == IdentityStatus.ACTIVE } shouldBe true
            f.step("join", StepKind.RUN, "a03").detail shouldBe "mail_timeout: no e-mail within 60s"
            summary.outcome shouldBe RunOutcome.FAILED
            summary.failedAgents shouldBe 1
            f.monitor.statuses
                .last { it.agentId == AgentId("a03") }
                .state shouldBe AgentState.FAILED
        }

    @Test
    fun `a failed assertion in setup also fails the actor`() =
        runTest {
            val f = fixture()
            f.verify.oracleVerdict = { _, input -> if (input.agentId == AgentId("a04")) Verdict.FAILED else Verdict.PASSED }
            val join =
                setupStep(
                    "join",
                    everyoneButAdmin(),
                ).copy(assertions = listOf(AssertionSpec.Oracle("/test/users/{self.email}", null, null, null)))

            val summary = f.runner().run(campaign(setup = listOf(join), steps = listOf(step("work", employees()))))

            f.agents.callsFor("work").map { it.agentId.value } shouldNotContain "a04"
            f.identities.statusReasons[summary.runId to AgentId("a04")] shouldBe "assertion_failed"
        }

    @Test
    fun `the run is aborted when the admin fails a setup step`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.agentId == AgentId("a01")) {
                    ActionOutcome(ActionStatus.FAILED, "registration rejected", failureReason = FailureReason.REGISTRATION_FAILED)
                } else {
                    ActionOutcome(ActionStatus.SUCCEEDED, "ok")
                }
            }

            val summary = f.runner().run(campaign(setup = setup(), steps = listOf(step("work", everyoneButAdmin()))))

            summary.outcome shouldBe RunOutcome.ABORTED
            f.agents.calls
                .map { it.scenarioStep }
                .distinct() shouldContainExactly listOf("owner_signup")
            val abort = f.system("abort").single()
            abort.detail!! shouldContain "the admin failed setup step 'owner_signup' (registration_failed)"
            abort.detail!! shouldContain "steps not run: join, work"
            f.evidence.runList
                .single()
                .result shouldBe RunResult.ABORTED
            f.browser.stops.get() shouldBe 1
            f.finalizer.calls.size shouldBe 1
            f.monitor.events.last() shouldBe "runFinished ABORTED"
        }

    @Test
    fun `the run is aborted when the admin has no browser session`() =
        runTest {
            val f = fixture()
            f.browser.failOpenFor = setOf("a01")

            val summary = f.runner().run(campaign(setup = setup(), steps = listOf(step("work", everyoneButAdmin()))))

            summary.outcome shouldBe RunOutcome.ABORTED
            f.agents.calls.shouldBeEmpty()
            f.system("open_session").single().detail!! shouldContain "browser_error"
            f.browser.sessions.values
                .all { it.closed } shouldBe true
        }

    @Test
    fun `a tester whose browser session cannot be opened is excluded but the others continue`() =
        runTest {
            val f = fixture()
            f.browser.failOpenFor = setOf("a05")

            val summary = f.runner().run(campaign(setup = setup(), steps = listOf(step("work", employees()))))

            summary.outcome shouldBe RunOutcome.FAILED
            summary.failedAgents shouldBe 1
            f.agents.callsFor("work").map { it.agentId.value } shouldBe listOf("a04", "a06", "a07")
            f.identities.statusReasons[summary.runId to AgentId("a05")] shouldBe "browser_error"
            f.steps("join", StepKind.SYSTEM).single().agentId shouldBe AgentId("a05")
        }

    @Test
    fun `only sign-up and login steps activate identities`() =
        runTest {
            val f = fixture()
            val campaign =
                campaign(
                    setup =
                        listOf(
                            setupStep("seed", admin(), StepAction.Run("seed_company")),
                            setupStep("prepare", employees(), StepAction.Run("read_email_code")),
                            setupStep("login", employees("IT"), StepAction.Run("login")),
                        ),
                )

            val summary = f.runner().run(campaign)

            val statuses = f.identities.findByRun(summary.runId).associate { it.agentId.value to it.status }
            statuses shouldBe
                mapOf(
                    "a01" to IdentityStatus.PLANNED,
                    "a02" to IdentityStatus.PLANNED,
                    "a03" to IdentityStatus.PLANNED,
                    "a04" to IdentityStatus.ACTIVE,
                    "a05" to IdentityStatus.PLANNED,
                    "a06" to IdentityStatus.ACTIVE,
                    "a07" to IdentityStatus.PLANNED,
                )
        }

    @Test
    fun `on_fail abort on a step stops the run after that step`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "announce") mailTimeout else ActionOutcome(ActionStatus.SUCCEEDED, "ok")
            }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("announce", admin(), onFail = OnFail.ABORT),
                            step("read", employees()),
                        ),
                )

            val summary = f.runner().run(campaign)

            summary.outcome shouldBe RunOutcome.ABORTED
            f.agents.callsFor("read").shouldBeEmpty()
            f.system("abort").single().detail!! shouldContain "step 'announce' failed and on_fail is abort"
        }

    @Test
    fun `on_fail abort at campaign level applies to every step`() =
        runTest {
            val f = fixture()
            f.verify.oracleVerdict = { _, _ -> Verdict.FAILED }
            val campaign =
                campaign(
                    onFail = OnFail.ABORT,
                    steps =
                        listOf(
                            step("announce", admin(), assertions = listOf(AssertionSpec.Oracle("/test/x", null, null, null))),
                            step("read", employees()),
                        ),
                )

            val summary = f.runner().run(campaign)

            summary.outcome shouldBe RunOutcome.ABORTED
            summary.assertionsFailed shouldBe 1
            f.agents.callsFor("read").shouldBeEmpty()
        }

    @Test
    fun `a step can override a campaign-level abort with continue`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "announce") mailTimeout else ActionOutcome(ActionStatus.SUCCEEDED, "ok")
            }
            val campaign =
                campaign(
                    onFail = OnFail.ABORT,
                    steps = listOf(step("announce", admin(), onFail = OnFail.CONTINUE), step("read", employees())),
                )

            val summary = f.runner().run(campaign)

            summary.outcome shouldBe RunOutcome.FAILED
            f.agents.callsFor("read").size shouldBe 4
        }

    @Test
    fun `a step whose expression matches nobody is skipped without failing the run`() =
        runTest {
            val f = fixture()

            val summary = f.runner().run(campaign(steps = listOf(step("finance", employees("Maliyyə")))))

            summary.outcome shouldBe RunOutcome.PASSED
            val skipped = f.steps("finance").single()
            skipped.kind shouldBe StepKind.SYSTEM
            skipped.agentId shouldBe null
            skipped.status shouldBe StepStatus.SKIPPED
        }

    @Test
    fun `the shared company id is registered as a run resource exactly once`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, runtime ->
                if (call.scenarioStep == "seed") runtime.shared.put(SharedRunState.COMPANY_ID, "c7")
                ActionOutcome(ActionStatus.SUCCEEDED, "ok")
            }
            val campaign =
                campaign(
                    setup = listOf(setupStep("seed", admin(), StepAction.Run("seed_company")), setupStep("join", everyoneButAdmin())),
                    steps = listOf(step("work", employees())),
                )

            val summary = f.runner().run(campaign, RunOptions(keepData = true))

            f.evidence.resourceList.map { it.runId to it.externalId } shouldContainExactly listOf(summary.runId to "c7")
            f.evidence.resourceList
                .single()
                .kind shouldBe "company"
            f.oracle.deleted.shouldBeEmpty()
        }

    @Test
    fun `a company the owner created without sharing its id is found by owner and torn down`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, runtime ->
                if (call.scenarioStep == "owner_signup") {
                    f.oracle.companies["c9"] = TestCompany("c9", "Pətək Test MMC", null, isTest = true)
                    f.oracle.owners[runtime.identity.email] = "c9"
                    ActionOutcome(ActionStatus.SUCCEEDED, "signed up")
                } else {
                    throw IllegalStateException("crash before the company id was shared")
                }
            }

            val summary = f.runner().run(campaign(setup = setup()))

            f.oracle.deleted shouldContainExactly listOf("c9")
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `a company that is not flagged is_test is never registered for teardown`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, runtime ->
                f.oracle.companies["prod"] = TestCompany("prod", "Real Company", null, isTest = false)
                f.oracle.owners[runtime.identity.email] = "prod"
                ActionOutcome(ActionStatus.SUCCEEDED, "ok")
            }

            f.runner().run(campaign(setup = listOf(setupStep("owner_signup", admin(), StepAction.Do("sign up")))))

            f.evidence.resourceList.shouldBeEmpty()
            f.oracle.deleted.shouldBeEmpty()
        }
}
