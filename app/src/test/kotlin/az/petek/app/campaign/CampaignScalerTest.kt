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

package az.petek.app.campaign

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.core.ids.RunTags
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.HmacPasswordDeriver
import az.petek.orchestration.domain.DefaultActorResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class CampaignScalerTest {
    @TempDir
    lateinit var dir: Path

    /** The shape of scenarios/kadrohr.yaml: 30 testers, 1 admin, 5 managers, 24 employees, 15 invite + 14 code. */
    private fun kadrohrLike(extraSteps: String = ""): Campaign {
        val file =
            dir.resolve("campaign.yaml").also {
                Files.writeString(
                    it,
                    """
                    campaign:
                      name: kadrohr-core
                      testers: 30
                      seed: 42
                      names: [Əli, Vəli, Sahil, Cəmil, Amil]
                      roles: {admin: 1, manager: 5, employee: 24}
                      departments: [IT, HR, Satış, Maliyyə, Əməliyyat]
                      registration: {invite: 15, company_code: 14}
                      budget: {max_steps_per_agent: 60, max_minutes: 40}
                    setup:
                      - id: owner_signup
                        actor: admin
                        do: "Sign up"
                    steps:
                      - id: announce
                        actor: admin
                        do: "Announce"
                      - id: read
                        actor: employee[*]
                        do: "Read"
                    """.trimIndent() + extraSteps,
                )
            }
        return YamlCampaignSource(URI("https://staging.kadrohr.test")).load(file)
    }

    @Test
    fun `seats are shared in proportion by largest remainder`() {
        CampaignScaler.apportion(11, listOf(5, 24)) shouldContainExactly listOf(2, 9)
        CampaignScaler.apportion(11, listOf(15, 14)) shouldContainExactly listOf(6, 5)
        CampaignScaler.apportion(4, listOf(1, 1)) shouldContainExactly listOf(2, 2)
        CampaignScaler.apportion(3, listOf(1, 1)) shouldContainExactly listOf(2, 1)
    }

    @Test
    fun `every used category keeps a seat when there are enough seats`() {
        CampaignScaler.apportion(2, listOf(5, 24)) shouldContainExactly listOf(1, 1)
        CampaignScaler.apportion(3, listOf(1, 28)) shouldContainExactly listOf(1, 2)
        CampaignScaler.apportion(1, listOf(5, 24)) shouldContainExactly listOf(0, 1)
    }

    @Test
    fun `unused categories get no seat`() {
        CampaignScaler.apportion(4, listOf(0, 24)) shouldContainExactly listOf(0, 4)
        CampaignScaler.apportion(0, listOf(5, 24)) shouldContainExactly listOf(0, 0)
    }

    @Test
    fun `scaling keeps one admin and the role and registration ratios`() {
        val scaled = CampaignScaler.scale(kadrohrLike(), 12).settings

        scaled.testers shouldBe 12
        scaled.roles shouldBe RoleQuota(admin = 1, manager = 2, employee = 9)
        scaled.registration shouldBe RegistrationQuota(invite = 6, companyCode = 5)
        scaled.name shouldBe "kadrohr-core (12 testers, scaled from 30)"
    }

    @Test
    fun `a scaled campaign stays valid`() {
        val scaled = CampaignScaler.scale(kadrohrLike(), 6)

        DefaultCampaignValidator().validate(scaled, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `given names beyond the new tester count are dropped`() {
        CampaignScaler.scale(kadrohrLike(), 3).settings.names shouldContainExactly listOf("Əli", "Vəli", "Sahil")
    }

    @Test
    fun `the steps and the rest of the campaign are unchanged`() {
        val original = kadrohrLike()

        val scaled = CampaignScaler.scale(original, 8)

        scaled.allSteps shouldBe original.allSteps
        scaled.sourceHash shouldBe original.sourceHash
        scaled.settings.departments shouldBe original.settings.departments
        scaled.settings.seed shouldBe original.settings.seed
    }

    @Test
    fun `asking for all testers changes nothing`() {
        val original = kadrohrLike()

        CampaignScaler.scale(original, 30) shouldBeSameInstanceAs original
    }

    @Test
    fun `more testers than the campaign has keeps the same shape`() {
        val scaled = CampaignScaler.scale(kadrohrLike(), 33).settings

        scaled.testers shouldBe 33
        scaled.roles shouldBe RoleQuota(admin = 1, manager = 6, employee = 26)
        scaled.registration shouldBe RegistrationQuota(invite = 17, companyCode = 15)
        scaled.names shouldContainExactly listOf("Əli", "Vəli", "Sahil", "Cəmil", "Amil")
        scaled.name shouldBe "kadrohr-core (33 testers, scaled from 30)"
    }

    @Test
    fun `every tester count stays a valid campaign with every manager invited`() {
        val original = kadrohrLike()
        val validator = DefaultCampaignValidator()

        (2..240).forEach { testers ->
            val scaled = CampaignScaler.scale(original, testers)

            validator.validate(scaled, emptySet()).shouldBeEmpty()
            (scaled.settings.registration.invite >= scaled.settings.roles.manager) shouldBe true
        }
    }

    @Test
    fun `a scaled-up campaign gets a generated identity for every tester`() {
        val campaign = CampaignScaler.scale(kadrohrLike(), 45)
        val generator = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))

        val identities =
            generator
                .generate(IdentitySpecs.of(campaign.settings, "test.kadrohr.com"), RunTags.forPlan(campaign.sourceHash, 42))
                .identities

        identities.size shouldBe 45
        identities.map { it.displayName.lowercase() }.distinct().size shouldBe 45
    }

    @Test
    fun `a campaign with only the admin cannot grow`() {
        val adminOnly =
            kadrohrLike().let {
                it.copy(
                    settings =
                        it.settings.copy(
                            testers = 1,
                            roles = RoleQuota(admin = 1, manager = 0, employee = 0),
                            registration = RegistrationQuota(invite = 0, companyCode = 0),
                        ),
                )
            }

        shouldThrow<ScalingException> { CampaignScaler.scale(adminOnly, 4) }.message shouldContain "only the admin"
    }

    @Test
    fun `fewer than one agent is refused`() {
        shouldThrow<ScalingException> { CampaignScaler.scale(kadrohrLike(), 0) }
    }

    @Test
    fun `the admin alone is refused when the campaign has other testers`() {
        shouldThrow<ScalingException> { CampaignScaler.scale(kadrohrLike(), 1) }.message shouldContain "at least 2"
    }

    @Test
    fun `steps that no scaled tester can run are found`() {
        val financeOnly = "\n  - id: finance_only\n    actor: employee[dept=Maliyyə, n=2]\n    do: \"Only for the second finance employee\""
        val campaign = CampaignScaler.scale(kadrohrLike(financeOnly), 6)
        val generator = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))
        val identities =
            generator
                .generate(IdentitySpecs.of(campaign.settings, "test.kadrohr.com"), RunTags.forPlan(campaign.sourceHash, 42))
                .identities

        CampaignScaler.uncoveredSteps(campaign, identities, DefaultActorResolver()).map { it.id } shouldContainExactly
            listOf("finance_only")
    }

    @Test
    fun `resizing up shares the new seats in the campaign's ratios and keeps a manager per department`() {
        val campaign = kadrohrLike()

        val resized = CampaignScaler.resize(campaign, 60)

        resized.settings.testers shouldBe 60
        resized.settings.roles shouldBe RoleQuota(admin = 1, manager = 10, employee = 49)
        resized.settings.registration.invite + resized.settings.registration.companyCode shouldBe 59
        (resized.settings.registration.invite >= resized.settings.roles.manager) shouldBe true
        resized.settings.name shouldBe "kadrohr-core"
        resized.sourceHash shouldBe campaign.sourceHash
        DefaultCampaignValidator().validate(resized, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `resizing up a small team still gives every department a manager while an employee is left`() {
        val small = CampaignScaler.scale(kadrohrLike(), 6)
        small.settings.roles.manager shouldBe 1

        val grown = CampaignScaler.resize(small.copy(settings = small.settings.copy(name = "kadrohr-core")), 8)

        grown.settings.roles shouldBe RoleQuota(admin = 1, manager = 5, employee = 2)
        grown.settings.registration.invite shouldBe 5
        DefaultCampaignValidator().validate(grown, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `resizing down works like scaling but keeps the campaign's name`() {
        val campaign = kadrohrLike()

        val resized = CampaignScaler.resize(campaign, 12)

        resized.settings.roles shouldBe CampaignScaler.scale(campaign, 12).settings.roles
        resized.settings.name shouldBe "kadrohr-core"
        CampaignScaler.resize(campaign, 30) shouldBeSameInstanceAs campaign
        shouldThrow<ScalingException> { CampaignScaler.resize(campaign, 0) }
    }
}
