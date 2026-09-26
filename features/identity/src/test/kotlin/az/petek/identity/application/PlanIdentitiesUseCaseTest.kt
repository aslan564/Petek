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

package az.petek.identity.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.identity.IdentityTestData.OTHER_RUN_TAG
import az.petek.identity.IdentityTestData.RUN_TAG
import az.petek.identity.IdentityTestData.generator
import az.petek.identity.IdentityTestData.spec
import az.petek.identity.domain.IdentityConflictException
import az.petek.identity.domain.IdentityStatus
import az.petek.identity.testing.InMemoryIdentityRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PlanIdentitiesUseCaseTest {
    private val repository = InMemoryIdentityRepository()
    private val useCase = PlanIdentitiesUseCase(generator(), repository)
    private val runId = RunId("0199a1b2-0000-7000-8000-000000000001")

    @Test
    fun `planning stores the generated identities under the run and returns them`() =
        runTest {
            val plan = useCase.execute(runId, RUN_TAG, spec())

            plan.runTag shouldBe RUN_TAG
            plan.identities shouldHaveSize 30
            repository.findByRun(runId) shouldBe plan.identities
        }

    @Test
    fun `planning twice gives the same identities`() =
        runTest {
            val first = useCase.execute(runId, RUN_TAG, spec())
            val second = useCase.execute(runId, RUN_TAG, spec())

            second shouldBe first
            repository.findByRun(runId) shouldBe first.identities
        }

    @Test
    fun `planning again replaces the previous registry of the run`() =
        runTest {
            useCase.execute(runId, RUN_TAG, spec())
            repository.updateStatus(runId, AgentId("a01"), IdentityStatus.ACTIVE)

            val smaller = useCase.execute(runId, OTHER_RUN_TAG, spec(testers = 10))

            repository.findByRun(runId) shouldBe smaller.identities
            repository.findByRun(runId).map { it.status }.toSet() shouldBe setOf(IdentityStatus.PLANNED)
        }

    @Test
    fun `a registry that cannot be built stores nothing`() =
        runTest {
            shouldThrow<IdentityConflictException> {
                useCase.execute(runId, RUN_TAG, spec(names = listOf("Əli", "Əli")))
            }
            repository.findByRun(runId).shouldBeEmpty()
        }

    @Test
    fun `other runs are left alone`() =
        runTest {
            val otherRun = RunId("0199a1b2-0000-7000-8000-000000000002")
            val other = useCase.execute(otherRun, OTHER_RUN_TAG, spec())

            useCase.execute(runId, RUN_TAG, spec(testers = 12))

            repository.findByRun(otherRun) shouldBe other.identities
        }
}
