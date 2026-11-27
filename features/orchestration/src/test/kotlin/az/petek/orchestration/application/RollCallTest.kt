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

import az.petek.core.ids.AgentId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.TestSharedRunState
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.step
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The roll call's own verdicts, on a run state set up by hand: a gap no path of the runner leaves today (every actor's
 * part is recorded), so only this way can it be shown what the roll call does if one ever appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RollCallTest {
    private fun tester(
        index: Int,
        role: Role,
    ) = Identity(
        agentId = AgentId.of(index),
        displayName = "T$index",
        email = "t$index@test.example.test",
        password = Secret("x"),
        phone = "+994500000000",
        role = role,
        department = "IT",
        registration = RegistrationMode.SELF,
    )

    @Test
    fun `a tester missing from a step of a run that went on is a failed record, as it counts against the run`() =
        runTest {
            val clock = VirtualClock(testScheduler)
            val ids = SequentialIdGenerator()
            val recorded = InMemoryEvidence()
            val read = step("read", employees())
            val run =
                RunState(
                    ids.runId(),
                    campaign(managers = 0, employees = 2, steps = listOf(read)),
                    RunOptions(),
                    clock.now(),
                    InProcessEventBus(clock, ids),
                    TestSharedRunState(),
                )
            run.identities = listOf(tester(1, Role.ADMIN), tester(2, Role.EMPLOYEE), tester(3, Role.EMPLOYEE))
            run.passes += PlannedPass(1, listOf(PassStep(read, run.identities)))
            run.passBegan(1)
            // `read` began with both employees, yet only a02's part has a record.
            run.stepChose("read", listOf(AgentId("a02"), AgentId("a03")))
            run.settle("read", AgentId("a02"))

            RollCall(DefaultActorResolver(), HarnessEvidence(recorded, InMemoryArtifactStore(), ids, clock)).call(run)

            recorded.stepList.single().let {
                it.action shouldBe NOT_REACHED_ACTION
                it.agentId shouldBe AgentId("a03")
                it.scenarioStep shouldBe "read"
                it.status shouldBe StepStatus.FAILED
                it.detail shouldBe "never_reached: the run went on, but a03 has no record of step 'read'"
            }
            run.outcome() shouldBe RunOutcome.FAILED
            // Pətək's own gap, not the tester's failure.
            run.tally.failedAgents shouldBe 0
        }
}
