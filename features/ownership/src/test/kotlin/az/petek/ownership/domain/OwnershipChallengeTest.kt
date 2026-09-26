/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.ownership.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

class OwnershipChallengeTest {
    private val token = OwnershipToken("0123456789abcdef0123456789abcdef")

    @Test
    fun `the file is on the target's own scheme, host and port`() {
        val challenge = OwnershipChallenge.of(URI("http://Stage.Example.com:8080/app/login?x=1"), token)

        challenge.host shouldBe "stage.example.com"
        challenge.fileUrl shouldBe URI("http://stage.example.com:8080/.well-known/petek-verification.txt")
        challenge.dnsName shouldBe "_petek-verification.stage.example.com"
        challenge.proofLine shouldBe "petek-verification=0123456789abcdef0123456789abcdef"
    }

    @Test
    fun `a site addressed by an IP literal has no DNS name for the record`() {
        OwnershipChallenge.of(URI("https://203.0.113.7/"), token).dnsName shouldBe null
    }

    @Test
    fun `a token is 32 lowercase hex characters`() {
        shouldThrow<IllegalArgumentException> { OwnershipToken("ABCDEF0123456789abcdef0123456789") }
        shouldThrow<IllegalArgumentException> { OwnershipToken("abc") }
    }

    @Test
    fun `the refusal names the file, the record, the line and where Pətək looked`() {
        val challenge = OwnershipChallenge.of(URI("https://stage.example.com/"), token)
        val status = OwnershipStatus.Unverified(challenge, listOf("https://stage.example.com/.well-known/petek-verification.txt: HTTP 404"))

        val message = OwnershipRequiredException(status).message.orEmpty()

        message shouldContain "nothing was tested on stage.example.com"
        message shouldContain
            "a file at https://stage.example.com/.well-known/petek-verification.txt containing the line: ${challenge.proofLine}"
        message shouldContain "a DNS TXT record _petek-verification.stage.example.com with the value: ${challenge.proofLine}"
        message shouldContain "Looked for it: https://stage.example.com/.well-known/petek-verification.txt: HTTP 404"
    }

    @Test
    fun `a record keeps its method by key`() {
        OwnershipMethod.ofKey("dns") shouldBe OwnershipMethod.DNS_TXT
        OwnershipRecord("a.example", OwnershipMethod.ofKey("file"), Instant.EPOCH).method shouldBe OwnershipMethod.WELL_KNOWN_FILE
    }
}
