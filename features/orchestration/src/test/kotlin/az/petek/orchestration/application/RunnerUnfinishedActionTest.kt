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
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.step
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * A tester stopped before it finished (stuck, or its action broke) leaves the site's records as they were: the checks
 * after its action are not evaluated, so its stop is never filed as a defect of the site (a missing read receipt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerUnfinishedActionTest {
    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler))

    /** The site keeps a read receipt only for a tester that opened the announcement. */
    private val receipt = AssertionSpec.Oracle("/test/receipts", null, null, "{self.email}")

    private fun RunnerFixture.checksOf(scenarioStep: String): Map<String, Pair<Verdict, String?>> =
        evidence.assertionList
            .filter { it.scenarioStep == scenarioStep }
            .associate { it.agentId!!.value to (it.verdict to if (it.verdict == Verdict.SKIPPED) it.note else null) }

    @Test
    fun `the checks after an action the watchdog stopped or an error ended are not evaluated, so they never blame the site`() =
        runTest {
            val f = fixture()
            val read = ConcurrentHashMap.newKeySet<AgentId>()
            f.agents.script = { call, _ ->
                when (call.agentId.value) {
                    "a02" -> {
                        awaitCancellation()
                    }

                    "a03" -> {
                        throw IllegalStateException("page crashed")
                    }

                    else -> {
                        read += call.agentId
                        ActionOutcome(ActionStatus.SUCCEEDED, "read it")
                    }
                }
            }
            f.verify.oracleVerdict = { _, input -> if (input.agentId in read) Verdict.PASSED else Verdict.FAILED }
            val campaign = campaign(managers = 0, employees = 4, steps = listOf(step("read", employees(), assertions = listOf(receipt))))

            val summary = f.runner().run(campaign, RunOptions(inactivityTimeout = 60.seconds))

            f.checksOf("read") shouldBe
                mapOf(
                    "a02" to (Verdict.SKIPPED to "not evaluated: the action did not complete (timeout)"),
                    "a03" to (Verdict.SKIPPED to "not evaluated: the action did not complete (error)"),
                    "a04" to (Verdict.PASSED to null),
                    "a05" to (Verdict.PASSED to null),
                )
            f.verify.actorCalls
                .map { it.second.agentId!!.value }
                .toSet() shouldBe setOf("a04", "a05")
            f.step("read", StepKind.DO, "a02").status shouldBe StepStatus.BLOCKED
            f.step("read", StepKind.DO, "a03").status shouldBe StepStatus.ERROR
            summary.assertionsFailed shouldBe 0
            summary.stepsFailed shouldBe 2
        }

    @Test
    fun `the checks after a refusal or a problem the agent reported still run, since both are answers about the site`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                when (call.agentId.value) {
                    "a02" -> ActionOutcome(ActionStatus.BLOCKED, "no read button", failureReason = FailureReason.PERMISSION_DENIED)
                    else -> ActionOutcome(ActionStatus.FAILED, "the list is empty", failureReason = FailureReason.PROBLEM_REPORTED)
                }
            }
            f.verify.oracleVerdict = { _, _ -> Verdict.FAILED }
            val campaign = campaign(managers = 0, employees = 2, steps = listOf(step("read", employees(), assertions = listOf(receipt))))

            val summary = f.runner().run(campaign)

            f.checksOf("read") shouldBe mapOf("a02" to (Verdict.FAILED to null), "a03" to (Verdict.FAILED to null))
            summary.assertionsFailed shouldBe 2
        }
}
