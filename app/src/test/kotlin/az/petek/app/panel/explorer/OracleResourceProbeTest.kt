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

package az.petek.app.panel.explorer

import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class OracleResourceProbeTest {
    private fun oracle(
        status: Int,
        body: String = "",
    ) = FakeTargetOracle().apply { responses["/test/notes/latest?by=owner%40test.portal.example"] = OracleResponse(status, null, body) }

    @Test
    fun `a resource is served when the test API answers with the object the trial made`() =
        runBlocking<Unit> {
            val marker = "Pətək sınaq 1a2b-1"

            OracleResourceProbe(
                oracle(200, "{\"id\":\"n1\",\"text\":\"$marker\"}"),
            ).serves("notes", "owner@test.portal.example", marker) shouldBe
                true
            OracleResourceProbe(
                oracle(200, "{\"id\":\"n0\",\"text\":\"older\"}"),
            ).serves("notes", "owner@test.portal.example", marker) shouldBe
                false
            OracleResourceProbe(oracle(404)).serves("notes", "owner@test.portal.example", marker) shouldBe false
        }

    @Test
    fun `an answer that says nothing, no test API or an unsafe name give no verdict`() =
        runBlocking<Unit> {
            OracleResourceProbe(oracle(500)).serves("notes", "owner@test.portal.example", "m").shouldBeNull()
            OracleResourceProbe(FakeTargetOracle(isAvailable = false)).serves("notes", "owner@test.portal.example", "m").shouldBeNull()
            OracleResourceProbe(oracle(200, "m")).serves("../companies", "owner@test.portal.example", "m").shouldBeNull()
        }
}
