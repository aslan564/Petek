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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WavesTest {
    private fun tester(index: Int) =
        Identity(
            agentId = AgentId.of(index),
            displayName = "T$index",
            email = "t$index@test.example.test",
            password = Secret("x"),
            phone = "+994500000000",
            role = Role.EMPLOYEE,
            department = null,
            registration = RegistrationMode.SELF,
        )

    @Test
    fun `testers are cut into waves of the given size in agent order`() {
        val waves = Waves.of(listOf(3, 10, 1, 5, 2, 4).map(::tester), 2)

        waves.map { wave -> wave.map { it.agentId.value } } shouldBe
            listOf(listOf("a01", "a02"), listOf("a03", "a04"), listOf("a05", "a10"))
    }

    @Test
    fun `without a wave size or when everyone fits into one wave there are no waves`() {
        val testers = (1..3).map(::tester)

        Waves.of(testers, null).shouldBeEmpty()
        Waves.of(testers, 3).shouldBeEmpty()
        Waves.of(testers, 10).shouldBeEmpty()
    }
}
