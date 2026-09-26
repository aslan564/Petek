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
    private val guarded = TargetPolicy(setOf("portal.example"), allowProduction = false)

    @Test
    fun `an allowed target comes back in its canonical spelling`() {
        PanelTargets.allowed(" HTTPS://Staging.Portal.example./login ", guarded, "target") shouldBe
            URI("https://staging.portal.example/login")
    }

    @Test
    fun `a production host is refused with the switch that allows it, in Azerbaijani, under the named field`() {
        val refusal = shouldThrow<PanelRequestException> { PanelTargets.allowed("https://Portal.example./", guarded, "target") }

        refusal.problems.single().field shouldBe "target"
        refusal.problems.single().message shouldContain "'portal.example' istehsal ünvanıdır"
        refusal.problems.single().message shouldContain "PETEK_ALLOW_PRODUCTION=true"
    }

    @Test
    fun `a production host passes when the configuration allows production`() {
        PanelTargets.allowed("https://portal.example", guarded.copy(allowProduction = true), "target") shouldBe
            URI("https://portal.example")
    }

    @Test
    fun `anything but a full http address without credentials is refused`() {
        listOf("portal.example", "ftp://portal.example", "https://", "not a url", "https://user:secret@portal.test").forEach { text ->
            val refusal = shouldThrow<PanelRequestException> { PanelTargets.allowed(text, guarded, "target") }
            refusal.problems.single().field shouldBe "target"
            refusal.message.orEmpty().contains("secret") shouldBe false
        }
    }

    @Test
    fun `two addresses are one site when scheme, host and effective port match`() {
        PanelTargets.sameSite(URI("https://portal.test/login"), URI("https://PORTAL.test:443/")) shouldBe true
        PanelTargets.sameSite(URI("http://portal.test"), URI("https://portal.test")) shouldBe false
        PanelTargets.sameSite(URI("http://127.0.0.1:18080"), URI("http://127.0.0.1:18081")) shouldBe false
        PanelTargets.site(URI("http://portal.test/x")) shouldBe "http://portal.test:80"
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
