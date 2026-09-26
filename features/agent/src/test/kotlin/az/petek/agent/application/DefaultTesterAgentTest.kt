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

package az.petek.agent.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.StepContext
import az.petek.agent.testing.AgentTestData
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.StepAction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultTesterAgentTest {
    private val runtime = AgentTestData.runtime(FakeBrowserSession(), AgentTestData.itEmployee)
    private val step = AgentTestData.step("ticket")

    private class RecordingLoop(
        private val outcome: ActionOutcome = ActionOutcome(ActionStatus.SUCCEEDED, "loop done", stepsTaken = 4),
        private val failure: Exception? = null,
    ) : AgentLoop {
        val calls = mutableListOf<Pair<String, StepContext>>()

        override suspend fun execute(
            runtime: AgentRuntime,
            instruction: String,
            step: StepContext,
        ): ActionOutcome {
            calls += instruction to step
            failure?.let { throw it }
            return outcome
        }
    }

    private class RecordingFunction(
        override val name: String,
    ) : RunFunction {
        val calls = mutableListOf<Map<String, String>>()

        override suspend fun execute(
            runtime: AgentRuntime,
            args: Map<String, String>,
            step: StepContext,
        ): ActionOutcome {
            calls += args
            return ActionOutcome(ActionStatus.SUCCEEDED, "$name ran for ${runtime.identity.agentId}")
        }
    }

    @Test
    fun `a do step goes to the agent loop with the rendered instruction`() =
        runTest {
            val loop = RecordingLoop()
            val agent = DefaultTesterAgent(runtime, loop, RunFunctionRegistry(emptyList()))

            val outcome = agent.perform(StepAction.Do("IT departamentinə ticket yaz: 'Noutbuk işləmir'"), step)

            outcome.summary shouldBe "loop done"
            loop.calls shouldContainExactly listOf("IT departamentinə ticket yaz: 'Noutbuk işləmir'" to step)
        }

    @Test
    fun `a run step goes to the named function with its arguments`() =
        runTest {
            val seed = RecordingFunction("seed_company")
            val login = RecordingFunction("login")
            val agent = DefaultTesterAgent(runtime, RecordingLoop(), RunFunctionRegistry(listOf(seed, login)))

            val outcome = agent.perform(StepAction.Run("seed_company", mapOf("company" to "X")), step)

            outcome.summary shouldBe "seed_company ran for a04"
            seed.calls shouldContainExactly listOf(mapOf("company" to "X"))
            login.calls shouldBe emptyList()
        }

    @Test
    fun `an unknown run function fails with a missing prerequisite`() =
        runTest {
            val agent = DefaultTesterAgent(runtime, RecordingLoop(), RunFunctionRegistry(listOf(RecordingFunction("login"))))

            val outcome = agent.perform(StepAction.Run("teleport"), step)

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            outcome.summary shouldContain "Unknown run function 'teleport'. Known: login."
        }

    @Test
    fun `a step without action succeeds without touching the browser or the model`() =
        runTest {
            val loop = RecordingLoop()
            val outcome = DefaultTesterAgent(runtime, loop, RunFunctionRegistry(emptyList())).perform(StepAction.None, step)

            outcome.status shouldBe ActionStatus.SUCCEEDED
            loop.calls shouldBe emptyList()
        }

    @Test
    fun `an unexpected failure becomes an error outcome for this agent only`() =
        runTest {
            val password = runtime.identity.password.reveal()
            val loop = RecordingLoop(failure = IllegalStateException("boom while typing $password"))

            val outcome = DefaultTesterAgent(runtime, loop, RunFunctionRegistry(emptyList())).perform(StepAction.Do("x"), step)

            outcome.status shouldBe ActionStatus.ERROR
            outcome.summary shouldContain "IllegalStateException: boom while typing"
            outcome.summary shouldNotContain password
        }

    @Test
    fun `cancellation is never swallowed`() =
        runTest {
            val loop = RecordingLoop(failure = CancellationException("watchdog"))

            shouldThrow<CancellationException> {
                DefaultTesterAgent(runtime, loop, RunFunctionRegistry(emptyList())).perform(StepAction.Do("x"), step)
            }
        }

    @Test
    fun `the factory binds a runtime to the shared loop and registry`() =
        runTest {
            val loop = RecordingLoop()
            val registry = RunFunctionRegistry(listOf(RecordingFunction("login")))
            val agent = DefaultTesterAgentFactory(loop, registry).create(runtime)

            agent.runtime shouldBeSameInstanceAs runtime
            agent.perform(StepAction.Run("login"), step).summary shouldBe "login ran for a04"
            agent.perform(StepAction.Do("y"), step).summary shouldBe "loop done"
        }
}
