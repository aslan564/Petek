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

package az.petek.ownership.application

import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipProbe
import az.petek.ownership.domain.OwnershipToken
import az.petek.ownership.domain.ProofLook
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI

class FirstFoundOwnershipProbeTest {
    private val target = URI("https://stage.example.com/")
    private val challenge = OwnershipChallenge.of(target, OwnershipToken("0".repeat(32)))
    private val asked = mutableListOf<String>()

    private fun probe(
        name: String,
        result: ProofLook,
    ) = OwnershipProbe { _, _ ->
        asked += name
        result
    }

    @Test
    fun `the first probe that finds the proof ends the look`() =
        runTest {
            val first =
                FirstFoundOwnershipProbe(
                    probe("file", ProofLook.Found(OwnershipMethod.WELL_KNOWN_FILE)),
                    probe("dns", ProofLook.Missing(listOf("dns"))),
                )

            first.look(target, challenge) shouldBe ProofLook.Found(OwnershipMethod.WELL_KNOWN_FILE)
            asked shouldBe listOf("file")
        }

    @Test
    fun `when none finds it, everything each one saw is reported`() =
        runTest {
            val first =
                FirstFoundOwnershipProbe(
                    probe("file", ProofLook.Missing(listOf("file: HTTP 404"))),
                    probe("dns", ProofLook.Missing(listOf("dns: no TXT record"))),
                )

            first.look(target, challenge) shouldBe ProofLook.Missing(listOf("file: HTTP 404", "dns: no TXT record"))
        }
}
