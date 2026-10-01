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
import az.petek.core.model.Role
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.HmacPasswordDeriver
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.DefaultActorResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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

    /** The shape of docs/examples/company-portal.yaml: 30 testers, 1 admin, 5 managers, 24 employees, 15 invite + 14 code. */
    private fun portalLike(extraSteps: String = ""): Campaign {
        val file =
            dir.resolve("campaign.yaml").also {
                Files.writeString(
                    it,
                    """
                    campaign:
                      name: portal-core
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
        return YamlCampaignSource(URI("https://staging.portal.test")).load(file)
    }

    /** An explorer draft of a site without sign-in: two visitors who only look ([VisitorRun]). */
    private fun visitors(registration: String = "{guest: 2}"): Campaign {
        val file =
            dir.resolve("visitors.yaml").also {
                Files.writeString(
                    it,
                    """
                    campaign:
                      name: explorer-site
                      testers: 2
                      seed: 42
                      tenant: none
                      roles: {anonymous: 2}
                      registration: $registration
                      budget: {max_steps_per_agent: 40, max_minutes: 20}
                    setup:
                      - id: gates
                        actor: anonymous[*]
                        run: register_and_login
                    steps:
                      - id: site-health
                        actor: anonymous[*]
                        run: {function: site_health, args: {pages: "/,/about", share: pages}}
                    """.trimIndent(),
                )
            }
        return YamlCampaignSource(URI("https://site.example")).load(file)
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
        val scaled = CampaignScaler.scale(portalLike(), 12).settings

        scaled.testers shouldBe 12
        scaled.roles shouldBe RoleQuota(admin = 1, manager = 2, employee = 9)
        scaled.registration shouldBe RegistrationQuota(invite = 6, companyCode = 5)
        scaled.name shouldBe "portal-core (12 testers, scaled from 30)"
    }

    @Test
    fun `a scaled campaign stays valid`() {
        val scaled = CampaignScaler.scale(portalLike(), 6)

        DefaultCampaignValidator().validate(scaled, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `given names beyond the new tester count are dropped`() {
        CampaignScaler.scale(portalLike(), 3).settings.names shouldContainExactly listOf("Əli", "Vəli", "Sahil")
    }

    @Test
    fun `the steps and the rest of the campaign are unchanged`() {
        val original = portalLike()

        val scaled = CampaignScaler.scale(original, 8)

        scaled.allSteps shouldBe original.allSteps
        scaled.sourceHash shouldBe original.sourceHash
        scaled.settings.departments shouldBe original.settings.departments
        scaled.settings.seed shouldBe original.settings.seed
    }

    @Test
    fun `asking for all testers changes nothing`() {
        val original = portalLike()

        CampaignScaler.scale(original, 30) shouldBeSameInstanceAs original
    }

    @Test
    fun `more testers than the campaign has keeps the same shape`() {
        val scaled = CampaignScaler.scale(portalLike(), 33).settings

        scaled.testers shouldBe 33
        scaled.roles shouldBe RoleQuota(admin = 1, manager = 6, employee = 26)
        scaled.registration shouldBe RegistrationQuota(invite = 17, companyCode = 15)
        scaled.names shouldContainExactly listOf("Əli", "Vəli", "Sahil", "Cəmil", "Amil")
        scaled.name shouldBe "portal-core (33 testers, scaled from 30)"
    }

    @Test
    fun `every tester count stays a valid campaign with every manager invited`() {
        val original = portalLike()
        val validator = DefaultCampaignValidator()

        (2..240).forEach { testers ->
            val scaled = CampaignScaler.scale(original, testers)

            validator.validate(scaled, emptySet()).shouldBeEmpty()
            (scaled.settings.registration.invite >= scaled.settings.roles.manager) shouldBe true
        }
    }

    @Test
    fun `a scaled-up campaign gets a generated identity for every tester`() {
        val campaign = CampaignScaler.scale(portalLike(), 45)
        val generator = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))

        val identities =
            generator
                .generate(IdentitySpecs.of(campaign.settings, "test.portal.example"), RunTags.forPlan(campaign.sourceHash, 42))
                .identities

        identities.size shouldBe 45
        identities.map { it.displayName.lowercase() }.distinct().size shouldBe 45
    }

    @Test
    fun `a campaign with only the admin cannot grow`() {
        val adminOnly =
            portalLike().let {
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
        shouldThrow<ScalingException> { CampaignScaler.scale(portalLike(), 0) }
    }

    @Test
    fun `the admin alone is refused when the campaign has other testers`() {
        shouldThrow<ScalingException> { CampaignScaler.scale(portalLike(), 1) }.message shouldContain "at least 2"
    }

    @Test
    fun `steps that no scaled tester can run are found`() {
        val financeOnly = "\n  - id: finance_only\n    actor: employee[dept=Maliyyə, n=2]\n    do: \"Only for the second finance employee\""
        val campaign = CampaignScaler.scale(portalLike(financeOnly), 6)
        val generator = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))
        val identities =
            generator
                .generate(IdentitySpecs.of(campaign.settings, "test.portal.example"), RunTags.forPlan(campaign.sourceHash, 42))
                .identities

        CampaignScaler.uncoveredSteps(campaign, identities, DefaultActorResolver()).map { it.id } shouldContainExactly
            listOf("finance_only")
    }

    /**
     * 50 testers of the shape the drafts write, 1 admin, 4 managers and 45 employees over five departments, in waves of
     * [waveSize]. With waves of 10 the admin is in every wave, a02..a05 (IT, HR, Satış, Maliyyə) are dealt one to each
     * of waves 1-4 and wave 5 has no manager at all.
     */
    private fun fiftyInWaves(
        waveSize: Int?,
        extraSteps: String = "",
    ): Campaign {
        val file =
            dir.resolve("fifty.yaml").also {
                Files.writeString(
                    it,
                    """
                    campaign:
                      name: fifty
                      testers: 50
                      seed: 42
                      ${waveSize?.let { size -> "wave_size: $size" }.orEmpty()}
                      roles: {admin: 1, manager: 4, employee: 45}
                      departments: [IT, HR, Satış, Maliyyə, Əməliyyat]
                      registration: {invite: 25, company_code: 24}
                      budget: {max_steps_per_agent: 60, max_minutes: 40}
                    setup:
                      - id: owner_signup
                        actor: admin
                        do: "Sign up"
                      - id: managers_join
                        actor: manager[*]
                        do: "Join"
                    steps:
                      - id: second_manager
                        actor: manager[n=2]
                        do: "Open the direct link as the second manager"
                      - id: it_post
                        actor: manager[IT]
                        do: "Post"
                        emits: posted
                      - id: managers_look
                        actor: manager[*]
                        do: "Look"
                      - id: read
                        actor: employee[*]
                        wait_for: posted
                      - id: announce
                        actor: admin
                        do: "Announce"
                        emits: announced
                    """.trimIndent() + extraSteps,
                )
            }
        return YamlCampaignSource(URI("https://staging.portal.test")).load(file)
    }

    private fun identitiesOf(campaign: Campaign): List<Identity> =
        DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))
            .generate(IdentitySpecs.of(campaign.settings, "test.portal.example"), RunTags.forPlan(campaign.sourceHash, 42))
            .identities

    @Test
    fun `a step is resolved wave by wave, so one whose actor no wave holds is found although the registry has one`() {
        val campaign = fiftyInWaves(waveSize = 10)
        val identities = identitiesOf(campaign)

        val gaps = CampaignScaler.stepsWithoutActors(campaign, identities, DefaultActorResolver())

        // `manager[n=2]` is a03 in the registry, but every wave holds one manager: it starts in wave 1 only (no later
        // wave has an actor for it, and it neither emits nor waits) and is never performed. The IT manager's post is
        // started again in every wave for its receivers, and only wave 1 has the IT manager. A setup step and a main
        // step that later waves start only for their own testers (`manager[*]`) are not skipped in wave 5.
        gaps.map { Triple(it.step.id, it.waves, it.covered) } shouldContainExactly
            listOf(Triple("second_manager", listOf(1), false), Triple("it_post", listOf(2, 3, 4, 5), true))
        CampaignScaler.uncoveredSteps(campaign, identities, DefaultActorResolver()).map { it.id } shouldContainExactly
            listOf("second_manager")
    }

    @Test
    fun `without waves a step is resolved against every tester of the run`() {
        val campaign = fiftyInWaves(waveSize = null)

        CampaignScaler.stepsWithoutActors(campaign, identitiesOf(campaign), DefaultActorResolver()).shouldBeEmpty()
    }

    @Test
    fun `the warnings name every step nobody performs, wave by wave, and what made it so`() {
        val campaign = fiftyInWaves(waveSize = 10)

        fun line(id: String) = campaign.allSteps.single { it.id == id }.line

        val warnings = CoverageWarnings.of(campaign, identitiesOf(campaign))

        warnings.take(2) shouldContainExactly
            listOf(
                "with campaign.wave_size 10 no tester matches 'manager[n=2]' in wave 1, every wave it starts in, so nobody " +
                    "performs step 'second_manager' (line ${line("second_manager")}) and the run fails for it (not_covered).",
                "with campaign.wave_size 10 no tester matches 'manager[IT]' in wave 2, 3, 4, 5, so step 'it_post' " +
                    "(line ${line("it_post")}) is skipped there; other waves perform it.",
            )
        warnings[2] shouldContain "step 'read' (line ${line("read")}) waits for 'posted' in wave 2, 3, 4, 5"
        warnings shouldHaveSize 3
        CoverageWarnings.of(campaign, identitiesOf(campaign), testers = 50).first() shouldContain
            "with --testers 50 and campaign.wave_size 10 no tester matches 'manager[n=2]'"
        // Without waves: the nine IT employees are there (the validator counts them), but the invitations are shared
        // out over the departments, so fewer than nine of them are invited; only the registry shows it.
        val alone = "\n  - id: alone\n    actor: employee[dept=IT, reg=invite, n=9]\n    do: \"Only the ninth invited IT employee\""
        val withoutWaves = fiftyInWaves(waveSize = null, extraSteps = alone)
        DefaultCampaignValidator().validate(withoutWaves, emptySet()).shouldBeEmpty()
        CoverageWarnings.of(withoutWaves, identitiesOf(withoutWaves)) shouldContainExactly
            listOf(
                "no tester matches 'employee[dept=IT, reg=invite, n=9]', so nobody performs step 'alone' " +
                    "(line ${withoutWaves.allSteps.last().line}) and the run fails for it (not_covered).",
            )
    }

    @Test
    fun `receivers the waves put without the tester who emits their event are found`() {
        val posting =
            "\n  - id: post\n    actor: manager[IT]\n    do: \"Post\"\n    emits: posted" +
                "\n  - id: see\n    actor: employee[*]\n    wait_for: posted" +
                "\n  - id: answer\n    actor: manager[HR]\n    wait_for: posted" +
                "\n  - id: note\n    actor: admin\n    do: \"Note\"\n    emits: noted" +
                "\n  - id: read_note\n    actor: employee[*]\n    wait_for: noted"
        val campaign = portalLike(posting)
        val generator = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))
        val identities =
            generator
                .generate(IdentitySpecs.of(campaign.settings, "test.portal.example"), RunTags.forPlan(campaign.sourceHash, 42))
                .identities

        fun gaps(waveSize: Int?) =
            CampaignScaler
                .waitsWithoutEmitter(
                    campaign.copy(settings = campaign.settings.copy(waveSize = waveSize)),
                    identities,
                    DefaultActorResolver(),
                ).map { Triple(it.step.id, it.waves, it.covered) }

        // a01 is the admin, in every wave: its note reaches every wave. a02 (IT) and a03 (HR) are the first managers,
        // dealt into waves 1 and 2, the employees into all: only wave 1 has the IT manager's post, and the HR manager
        // never shares a wave with it.
        gaps(10) shouldContainExactly listOf(Triple("see", listOf(2, 3), true), Triple("answer", listOf(2), false))
        gaps(6) shouldContainExactly listOf(Triple("see", listOf(2, 3, 4, 5), true), Triple("answer", listOf(2), false))
        gaps(null).shouldBeEmpty()
    }

    @Test
    fun `races that the waves leave with one racer are found with those waves`() {
        val race =
            "\n  - id: pair\n    actor: [\"manager[IT]\", \"manager[HR]\"]\n    parallel: true\n    do: \"Approve the same ticket\"" +
                "\n    assert:\n      - only_one_succeeds: {request: \"POST .*/approve\"}" +
                "\n  - id: all\n    actor: manager[*]\n    parallel: true\n    do: \"Approve the same ticket\"" +
                "\n    assert:\n      - only_one_succeeds: {request: \"POST .*/approve\"}"
        val campaign = portalLike(race)
        val generator = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))
        val identities =
            generator
                .generate(IdentitySpecs.of(campaign.settings, "test.portal.example"), RunTags.forPlan(campaign.sourceHash, 42))
                .identities

        fun split(waveSize: Int?) =
            CampaignScaler
                .racesSplitByWaves(
                    campaign.copy(settings = campaign.settings.copy(waveSize = waveSize)),
                    identities,
                    DefaultActorResolver(),
                ).map { it.step.id to it.waves }

        // The racers of a step share a wave: the IT and HR managers always race together. All five managers do not
        // fit into waves of two: a02..a05 race in pairs, a06 is left alone in wave 3; waves of three hold 3 and 2.
        split(2) shouldContainExactly listOf("all" to listOf(3))
        split(3).shouldBeEmpty()
        split(null).shouldBeEmpty()
    }

    @Test
    fun `resizing up shares the new seats in the campaign's ratios and keeps a manager per department`() {
        val campaign = portalLike()

        val resized = CampaignScaler.resize(campaign, 60)

        resized.settings.testers shouldBe 60
        resized.settings.roles shouldBe RoleQuota(admin = 1, manager = 10, employee = 49)
        resized.settings.registration.invite + resized.settings.registration.companyCode shouldBe 59
        (resized.settings.registration.invite >= resized.settings.roles.manager) shouldBe true
        resized.settings.name shouldBe "portal-core"
        resized.sourceHash shouldBe campaign.sourceHash
        DefaultCampaignValidator().validate(resized, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `resizing up a small team still gives every department a manager while an employee is left`() {
        val small = CampaignScaler.scale(portalLike(), 6)
        small.settings.roles.manager shouldBe 1

        val grown = CampaignScaler.resize(small.copy(settings = small.settings.copy(name = "portal-core")), 8)

        grown.settings.roles shouldBe RoleQuota(admin = 1, manager = 5, employee = 2)
        grown.settings.registration.invite shouldBe 5
        DefaultCampaignValidator().validate(grown, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `resizing down works like scaling but keeps the campaign's name`() {
        val campaign = portalLike()

        val resized = CampaignScaler.resize(campaign, 12)

        resized.settings.roles shouldBe CampaignScaler.scale(campaign, 12).settings.roles
        resized.settings.name shouldBe "portal-core"
        CampaignScaler.resize(campaign, 30) shouldBeSameInstanceAs campaign
        shouldThrow<ScalingException> { CampaignScaler.resize(campaign, 0) }
    }

    @Test
    fun `a site without companies grows to the asked count with its own roles and gates`() {
        val resized = CampaignScaler.resize(visitors(), 30)

        resized.settings.testers shouldBe 30
        resized.settings.roles.total shouldBe 30
        resized.settings.roles.count(checkNotNull(Role.fromKey("anonymous"))) shouldBe 30
        resized.settings.registration shouldBe RegistrationQuota(invite = 0, companyCode = 0, guest = 30)
        resized.settings.name shouldBe "explorer-site"
        DefaultCampaignValidator().validate(resized, setOf("register_and_login", "site_health")).shouldBeEmpty()
    }

    @Test
    fun `testers who sign in with the owner's accounts never outnumber the accounts`() {
        val resized = CampaignScaler.resize(visitors(registration = "{login: 1, self: 1}"), 10)

        resized.settings.registration shouldBe RegistrationQuota(invite = 0, companyCode = 0, self = 9, login = 1)
        shouldThrow<ScalingException> { CampaignScaler.resize(visitors(registration = "{login: 2}"), 5) }.message shouldContain
            "need more accounts"
    }

    @Test
    fun `a site without companies shrinks too`() {
        val resized = CampaignScaler.resize(CampaignScaler.resize(visitors(), 30), 1)

        resized.settings.registration shouldBe RegistrationQuota(invite = 0, companyCode = 0, guest = 1)
        resized.settings.roles.total shouldBe 1
    }
}
