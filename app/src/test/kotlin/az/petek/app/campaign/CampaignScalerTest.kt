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
        scaled.name shouldBe "kadrohr-core (12 of 30 agents)"
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
    fun `more agents than testers is refused`() {
        shouldThrow<ScalingException> { CampaignScaler.scale(kadrohrLike(), 31) }.message shouldContain "only reduce"
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
}
