/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.infrastructure

import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipToken
import az.petek.ownership.domain.ProofLook
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI

class DnsProofRecordTest {
    private val target = URI("https://stage.example.com/")
    private val challenge = OwnershipChallenge.of(target, OwnershipToken("0123456789abcdef0123456789abcdef"))
    private val asked = mutableListOf<String>()

    private fun record(vararg values: String) =
        DnsProofRecord { name ->
            asked += name
            values.toList()
        }

    @Test
    fun `the record under the verification label with this token is the proof`() =
        runTest {
            record("v=spf1 -all", challenge.proofLine).look(target, challenge) shouldBe ProofLook.Found(OwnershipMethod.DNS_TXT)
            asked shouldBe listOf("_petek-verification.stage.example.com")
        }

    @Test
    fun `no record and a record with another token are told apart`() =
        runTest {
            record().look(target, challenge) shouldBe
                ProofLook.Missing(listOf("DNS TXT _petek-verification.stage.example.com: no TXT record"))
            record("petek-verification=ffffffffffffffffffffffffffffffff").look(target, challenge) shouldBe
                ProofLook.Missing(listOf("DNS TXT _petek-verification.stage.example.com: no TXT value ${challenge.proofLine}"))
        }

    @Test
    fun `a failed lookup is a reason, not an exception`() =
        runTest {
            DnsProofRecord { error("DNS server timed out") }.look(target, challenge) shouldBe
                ProofLook.Missing(listOf("DNS TXT _petek-verification.stage.example.com: DNS server timed out"))
        }

    @Test
    fun `a site addressed by an IP is not looked up`() =
        runTest {
            val ip = URI("https://203.0.113.7/")
            val result = record(challenge.proofLine).look(ip, OwnershipChallenge.of(ip, challenge.token))

            (result is ProofLook.Missing) shouldBe true
            asked shouldBe emptyList()
        }

    @Test
    fun `TXT strings are unquoted and joined as the owner published them`() {
        TxtValues.join("\"petek-verification=abc\"") shouldBe "petek-verification=abc"
        TxtValues.join("\"petek-verif\" \"ication=abc\"") shouldBe "petek-verification=abc"
        TxtValues.join("petek-verification=abc") shouldBe "petek-verification=abc"
        TxtValues.join("\"say \\\"hi\\\"\"") shouldBe "say \"hi\""
    }
}
