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
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.identity.IdentityTestData.OTHER_RUN_TAG
import az.petek.identity.IdentityTestData.RUN_TAG
import az.petek.identity.IdentityTestData.generator
import az.petek.identity.IdentityTestData.spec
import az.petek.identity.domain.GivenAccount
import az.petek.identity.domain.IdentityConflictException
import az.petek.identity.domain.IdentityStatus
import az.petek.identity.testing.InMemoryIdentityRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    @Test
    fun `planning ahead returns the testers on the owner's accounts but stores only the generated ones`() =
        runTest {
            val plan = useCase.planAhead(runId, RUN_TAG, loginSpec())

            plan.identities shouldHaveSize 4
            plan.identities.single { it.registration == RegistrationMode.LOGIN }.email shouldBe OWNER_EMAIL
            val stored = repository.findByRun(runId)
            stored shouldBe plan.identities.filter { it.registration != RegistrationMode.LOGIN }
            stored.map { it.email } shouldNotContain OWNER_EMAIL
            stored.map { it.password.reveal() } shouldNotContain OWNER_PASSWORD
        }

    @Test
    fun `planning ahead a registry the run could not store fails before storing any of it`() =
        runTest {
            // The editor a01 is given the name of the owner's account that the reader a02 signs in with.
            val error =
                shouldThrow<IdentityConflictException> { useCase.planAhead(runId, RUN_TAG, loginSpec(names = listOf("Reader One"))) }

            error.message.orEmpty() shouldContain "duplicate display name Reader One (a01, a02)"
            repository.findByRun(runId).shouldBeEmpty()
        }

    /** Four testers without companies, given [names] first; one reader signs in with the owner's reader account. */
    private fun loginSpec(names: List<String> = emptyList()) =
        spec(testers = 4, names = names, admins = 0, managers = 0, employees = 0, departments = emptyList(), inviteCount = 0)
            .copy(
                companies = false,
                ownRoles = linkedMapOf(checkNotNull(Role.fromKey("editor")) to 1, READER to 3),
                gates = mapOf(RegistrationMode.SELF to 3, RegistrationMode.LOGIN to 1),
                accounts = listOf(GivenAccount(READER, OWNER_EMAIL, Secret(OWNER_PASSWORD), "Reader One")),
            )

    private companion object {
        val READER = checkNotNull(Role.fromKey("reader"))
        const val OWNER_EMAIL = "owner-reader@example.com"
        const val OWNER_PASSWORD = "given-password"
    }
}
