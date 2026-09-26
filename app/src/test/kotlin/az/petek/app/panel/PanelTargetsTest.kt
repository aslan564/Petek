/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

import az.petek.core.security.TargetPolicy
import az.petek.core.testing.FakeHarnessClock
import az.petek.dashboard.domain.PanelRequestException
import az.petek.ownership.domain.OwnershipChallenge
import az.petek.ownership.domain.OwnershipStatus
import az.petek.ownership.domain.OwnershipToken
import az.petek.ownership.testing.OwnershipTestKit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI

class PanelTargetsTest {
    private val guarded = TargetPolicy(setOf("kadrohr.com"), allowProduction = false)

    @Test
    fun `an allowed target comes back in its canonical spelling`() {
        PanelTargets.allowed(" HTTPS://Staging.KadroHR.com./login ", guarded, "target") shouldBe URI("https://staging.kadrohr.com/login")
    }

    @Test
    fun `a production host is refused with the switch that allows it, in Azerbaijani, under the named field`() {
        val refusal = shouldThrow<PanelRequestException> { PanelTargets.allowed("https://KadroHR.com./", guarded, "target") }

        refusal.problems.single().field shouldBe "target"
        refusal.problems.single().message shouldContain "'kadrohr.com' istehsal ünvanıdır"
        refusal.problems.single().message shouldContain "PETEK_ALLOW_PRODUCTION=true"
    }

    @Test
    fun `a production host passes when the configuration allows production`() {
        PanelTargets.allowed("https://kadrohr.com", guarded.copy(allowProduction = true), "target") shouldBe URI("https://kadrohr.com")
    }

    @Test
    fun `anything but a full http address without credentials is refused`() {
        listOf("kadrohr.com", "ftp://kadrohr.com", "https://", "not a url", "https://user:secret@kadro.test").forEach { text ->
            val refusal = shouldThrow<PanelRequestException> { PanelTargets.allowed(text, guarded, "target") }
            refusal.problems.single().field shouldBe "target"
            refusal.message.orEmpty().contains("secret") shouldBe false
        }
    }

    @Test
    fun `two addresses are one site when scheme, host and effective port match`() {
        PanelTargets.sameSite(URI("https://kadro.test/login"), URI("https://KADRO.test:443/")) shouldBe true
        PanelTargets.sameSite(URI("http://kadro.test"), URI("https://kadro.test")) shouldBe false
        PanelTargets.sameSite(URI("http://127.0.0.1:18080"), URI("http://127.0.0.1:18081")) shouldBe false
        PanelTargets.site(URI("http://kadro.test/x")) shouldBe "http://kadro.test:80"
    }

    private val token = OwnershipToken("0123456789abcdef0123456789abcdef")

    private fun unverified(target: String) =
        OwnershipStatus.Unverified(
            OwnershipChallenge.of(URI(target), token),
            listOf("$target/.well-known/petek-verification.txt: HTTP 404"),
        )

    @Test
    fun `the proof for a named host offers the file and the DNS record, and says where it looked`() {
        val text = PanelTargets.proofHowTo(unverified("https://stage.example.com"))

        text shouldContain "https://stage.example.com/.well-known/petek-verification.txt"
        text shouldContain "petek-verification=0123456789abcdef0123456789abcdef"
        text shouldContain "DNS-ə TXT qeydi əlavə edin: _petek-verification.stage.example.com"
        text shouldContain "Yoxlanıldı: https://stage.example.com/.well-known/petek-verification.txt: HTTP 404"
    }

    @Test
    fun `the proof for an IP address offers only the file, as an address has no DNS name`() {
        val text = PanelTargets.proofHowTo(unverified("https://203.0.113.7"))

        text shouldContain "https://203.0.113.7/.well-known/petek-verification.txt"
        text shouldNotContain "DNS"
    }

    @Test
    fun `an unproved public site is refused under the named field, a local one passes`() =
        runBlocking<Unit> {
            val ownership = OwnershipTestKit.unowned(FakeHarnessClock(), local = setOf("127.0.0.1"))

            val refused = shouldThrow<PanelRequestException> { PanelTargets.owned(URI("https://stage.example.com"), ownership, "target") }
            PanelTargets.owned(URI("http://127.0.0.1:8080"), ownership, "target")

            refused.problems.single().field shouldBe "target"
            refused.problems.single().message shouldContain "stage.example.com üzərində heç nə test edilmədi"
        }
}
