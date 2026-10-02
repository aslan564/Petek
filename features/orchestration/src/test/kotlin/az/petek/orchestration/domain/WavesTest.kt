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

package az.petek.orchestration.domain

import az.petek.core.ids.AgentId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.identity.domain.Identity
import az.petek.orchestration.testing.managers
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WavesTest {
    private fun tester(
        index: Int,
        role: Role = Role.EMPLOYEE,
        department: String? = null,
    ) = Identity(
        agentId = AgentId.of(index),
        displayName = "T$index",
        email = "t$index@test.example.test",
        password = Secret("x"),
        phone = "+994500000000",
        role = role,
        department = department,
        registration = RegistrationMode.SELF,
    )

    /** a01 admin, a02..a05 managers (IT, HR, IT, HR), a06..a13 employees. */
    private val company =
        listOf(tester(1, Role.ADMIN)) +
            (2..5).map { tester(it, Role.MANAGER, if (it % 2 == 0) "IT" else "HR") } +
            (6..13).map { tester(it, Role.EMPLOYEE, "IT") }

    private fun List<List<Identity>>.ids() = map { wave -> wave.map { it.agentId.value } }

    @Test
    fun `a role nobody else has is live in every wave, and only it`() {
        val plan = Waves.plan(company, 4).shouldNotBeNull()

        plan.residents.map { it.agentId.value } shouldContainExactly listOf("a01")
        plan.waves.flatten().none { it.role == Role.ADMIN } shouldBe true
        plan.waves.indices.forEach { plan.live(it).first().agentId shouldBe AgentId("a01") }
        plan.maxLive shouldBe 5
    }

    @Test
    fun `every role is dealt into the waves in turn`() {
        val plan = Waves.plan(company, 4).shouldNotBeNull()

        plan.waves.ids() shouldContainExactly
            listOf(
                listOf("a02", "a05", "a08", "a11"),
                listOf("a03", "a06", "a09", "a12"),
                listOf("a04", "a07", "a10", "a13"),
            )
        plan.waves.map { wave -> wave.count { it.role == Role.MANAGER } } shouldContainExactly listOf(2, 1, 1)
    }

    @Test
    fun `the racers of one step share a wave`() {
        val plan = Waves.plan(company, 4, races = listOf(managers("IT"), managers("HR"))).shouldNotBeNull()

        // a02 and a04 (IT) race each other, a03 and a05 (HR) too: each pair is in one wave.
        val waveOf =
            plan.waves
                .withIndex()
                .flatMap { (i, wave) -> wave.map { it.agentId.value to i } }
                .toMap()
        waveOf["a02"] shouldBe waveOf["a04"]
        waveOf["a03"] shouldBe waveOf["a05"]
        plan.waves.forEach { (it.size <= 4) shouldBe true }
        plan.waves.flatten().size shouldBe 12
    }

    @Test
    fun `a race with more racers than a wave holds is split into wave-sized parts`() {
        val plan = Waves.plan(company, 3, races = listOf(managers())).shouldNotBeNull()

        val managersPerWave = plan.waves.map { wave -> wave.count { it.role == Role.MANAGER } }.filter { it > 0 }
        managersPerWave shouldContainExactly listOf(3, 1)
    }

    @Test
    fun `without a size, or when all but the residents fit into one wave, there are no waves`() {
        Waves.plan(company, null).shouldBeNull()
        Waves.plan(company, 12).shouldBeNull()
        Waves.plan(company, 0).shouldBeNull()
        Waves.plan(company, 11).shouldNotBeNull()
    }

    @Test
    fun `the same testers, size and races always give the same waves`() {
        val shuffled = company.shuffled(kotlin.random.Random(7))

        Waves.plan(shuffled, 4, races = listOf(managers("IT"))) shouldBe Waves.plan(company, 4, races = listOf(managers("IT")))
    }
}
