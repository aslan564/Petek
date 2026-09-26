/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
