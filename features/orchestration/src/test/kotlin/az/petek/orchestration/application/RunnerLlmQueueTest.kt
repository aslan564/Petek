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
import az.petek.campaign.domain.StepAction
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.llm.application.ConcurrencyLimitedLlmClient
import az.petek.llm.application.RetryingLlmClient
import az.petek.llm.domain.LlmRequest
import az.petek.llm.testing.ScriptedLlmClient
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.TaskState
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Many testers share a few AI slots (`PETEK_LLM_CONCURRENCY`): a tester whose next decision waits minutes for the
 * others' calls is not stuck, and the run's inactivity watchdog (120 s) must never block it for that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerLlmQueueTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    @Test
    fun `100 testers on 6 AI slots at 15 s a decision all finish and nobody is blocked`() =
        runTest {
            val f = fixture()
            val decisionsEach = 3
            // The decorators the app puts around the provider: retried, limited to 6 calls in flight.
            val llm =
                RetryingLlmClient(
                    ConcurrencyLimitedLlmClient(
                        ScriptedLlmClient { _ ->
                            delay(15.seconds)
                            JsonObject(emptyMap())
                        },
                        permits = 6,
                    ),
                )
            // A `do` turn as the agent loop takes it: look at the page, ask the AI, act on the page.
            f.agents.script = { call, runtime ->
                repeat(decisionsEach) { turn ->
                    runtime.session.snapshot()
                    llm.complete(LlmRequest("system", emptyList(), JsonObject(emptyMap()), label = "${call.agentId}/$turn"))
                    runtime.session.click(turn)
                }
                ActionOutcome(ActionStatus.SUCCEEDED, "read it", stepsTaken = decisionsEach)
            }
            val campaign =
                campaign(
                    managers = 0,
                    employees = 100,
                    steps = listOf(step("read", employees(), StepAction.Do("Read the announcement"))),
                )

            val summary = f.runner().run(campaign)

            val actions = f.steps("read", StepKind.DO)
            actions shouldHaveSize 100
            actions.filter { it.status != StepStatus.PASSED }.shouldBeEmpty()
            f.monitor.tasks
                .filter { it.state == TaskState.BLOCKED }
                .shouldBeEmpty()
            f.monitor.statuses
                .filter { it.state == AgentState.BLOCKED }
                .shouldBeEmpty()
            summary.outcome shouldBe RunOutcome.PASSED
            summary.stepsPassed shouldBe 100
            // 300 decisions through 6 slots at 15 s each: the queue, not the watchdog, sets the pace.
            currentTime shouldBe 750_000
        }

    @Test
    fun `a decision slower than the inactivity timeout but within the provider's limit blocks nobody, also in setup`() =
        runTest {
            val f = fixture()
            // A thinking model on a loaded machine: 150 s for one decision, past the 120 s of inactivity.
            val llm =
                RetryingLlmClient(
                    ConcurrencyLimitedLlmClient(
                        ScriptedLlmClient { _ ->
                            delay(150.seconds)
                            JsonObject(emptyMap())
                        },
                        permits = 6,
                    ),
                )
            f.agents.script = { call, runtime ->
                runtime.session.snapshot()
                llm.complete(LlmRequest("system", emptyList(), JsonObject(emptyMap()), label = "${call.agentId}/${call.scenarioStep}"))
                runtime.session.click(1)
                ActionOutcome(ActionStatus.SUCCEEDED, "done")
            }
            val campaign =
                campaign(
                    managers = 0,
                    employees = 3,
                    setup = listOf(setupStep("join", employees(), StepAction.Do("Join the company"))),
                    steps = listOf(step("read", employees(), StepAction.Do("Read the announcement"))),
                )

            val summary = f.runner().run(campaign)

            f.steps("join", StepKind.DO).map { it.status } shouldBe List(3) { StepStatus.PASSED }
            // Nobody was dropped after setup: all three read.
            f.steps("read", StepKind.DO).map { it.status } shouldBe List(3) { StepStatus.PASSED }
            f.monitor.tasks
                .filter { it.state == TaskState.BLOCKED }
                .shouldBeEmpty()
            summary.outcome shouldBe RunOutcome.PASSED
            currentTime shouldBe 300_000
        }
}
