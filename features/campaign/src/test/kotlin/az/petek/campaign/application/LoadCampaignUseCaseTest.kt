package az.petek.campaign.application

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.CampaignValidator
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.ValidationIssue
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.campaign.testing.KNOWN_RUN_FUNCTIONS
import az.petek.campaign.testing.campaign
import az.petek.campaign.testing.contractDemoScenario
import az.petek.campaign.testing.kadrohrScenario
import az.petek.campaign.testing.step
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class LoadCampaignUseCaseTest {
    @TempDir
    lateinit var dir: Path

    private fun useCase(targetOverride: URI? = null) = LoadCampaignUseCase(YamlCampaignSource(targetOverride), DefaultCampaignValidator())

    private fun write(yaml: String): Path = dir.resolve("campaign.yaml").also { Files.writeString(it, yaml.trimIndent()) }

    @Test
    fun `the real kadrohr campaign loads and validates`() {
        val campaign = useCase().execute(kadrohrScenario(), KNOWN_RUN_FUNCTIONS)
        campaign.settings.testers shouldBe 30
        campaign.allSteps.size shouldBe 9
    }

    @Test
    fun `the target override reaches the validated campaign`() {
        useCase(URI("http://127.0.0.1:8080")).execute(kadrohrScenario(), KNOWN_RUN_FUNCTIONS).settings.target shouldBe
            URI("http://127.0.0.1:8080")
    }

    @Test
    fun `the real campaign needs its run functions`() {
        val error = shouldThrow<CampaignValidationException> { useCase().execute(kadrohrScenario(), setOf("login")) }
        error.issues.map { it.message.substringAfter("unknown run function ").substringBefore(" ") } shouldContainExactly
            listOf("'register_owner'", "'seed_company'", "'register_and_login'")
    }

    @Test
    fun `the contract demo campaign loads and validates`() {
        val campaign = useCase().execute(contractDemoScenario(), KNOWN_RUN_FUNCTIONS)
        campaign.settings.name shouldBe "contract-demo"
        campaign.allSteps.size shouldBe 9
    }

    @Test
    fun `cross-field problems are reported with the lines of the file`() {
        val file =
            write(
                """
                campaign:
                  target: https://staging.kadrohr.test
                  testers: 4
                  seed: 1
                  roles: {admin: 1, manager: 1, employee: 1}
                  departments: [IT]
                  budget: {max_steps_per_agent: 10, max_minutes: 5}
                steps:
                  - id: read
                    actor: employee
                    wait_for: created
                    do: "Oxu {last_id}"
                    assert:
                      - latency_max: {ms: 100}
                  - id: make
                    actor: admin
                    run: make_things
                    emits: created
                  - id: idle
                    actor: manager[HR]
                """,
            )
        val issues = shouldThrow<CampaignValidationException> { useCase().execute(file, KNOWN_RUN_FUNCTIONS) }.issues

        fun lineOf(fragment: String): Int? = issues.single { fragment in it.message }.line

        lineOf("roles add up to 3") shouldBe 5
        lineOf("waits for 'created'") shouldBe 11
        lineOf("{last_id} needs an event") shouldBe 12
        lineOf("must come after a visible_text") shouldBe 14
        lineOf("unknown run function 'make_things'") shouldBe 17
        lineOf("names department 'HR'") shouldBe 20
        lineOf("has neither do nor run") shouldBe 19
        issues.size shouldBe 7
    }

    @Test
    fun `a registration that lets managers join with the company code is rejected with its line`() {
        val file =
            write(
                """
                campaign:
                  target: https://staging.kadrohr.test
                  testers: 7
                  seed: 1
                  roles: {admin: 1, manager: 2, employee: 4}
                  departments: [IT, HR]
                  registration: {invite: 1, company_code: 5}
                  budget: {max_steps_per_agent: 10, max_minutes: 5}
                steps:
                  - id: make
                    actor: admin
                    do: x
                """,
            )
        val issue = shouldThrow<CampaignValidationException> { useCase().execute(file, KNOWN_RUN_FUNCTIONS) }.issues.single()
        issue.line shouldBe 7
        issue.message shouldContain "campaign.registration.invite is 1 but must be at least roles.manager (2)"
    }

    @Test
    fun `an endless wait and an off-target oracle path are rejected with their lines`() {
        val file =
            write(
                """
                campaign:
                  target: https://staging.kadrohr.test
                  testers: 2
                  seed: 1
                  roles: {admin: 1, manager: 0, employee: 1}
                  departments: [IT]
                  budget: {max_steps_per_agent: 10, max_minutes: 5}
                steps:
                  - id: make
                    actor: admin
                    do: x
                    emits: created
                  - id: read
                    actor: employee
                    wait_for: {event: created, timeout_s: 1e300}
                    do: y
                    assert:
                      - oracle: {path: "https://elsewhere.test/test/x/{last_id}", field: id}
                """,
            )
        val issues = shouldThrow<CampaignValidationException> { useCase().execute(file, KNOWN_RUN_FUNCTIONS) }.issues
        issues.single { "wait_for timeout must be finite" in it.message }.line shouldBe 15
        issues.single { "path must be a path on the target" in it.message }.line shouldBe 18
        issues.size shouldBe 2
    }

    @Test
    fun `schema problems stop the load before validation`() {
        val file = write("campaign:\n  testers: 1\n  colour: blue")
        val issues = shouldThrow<CampaignValidationException> { useCase().execute(file, KNOWN_RUN_FUNCTIONS) }.issues
        issues.single { "unknown key 'colour'" in it.message }.line shouldBe 3
    }

    /** Hand-written validator fake: returns [issues] and records what it was asked. */
    private class RecordingValidator(
        private val issues: List<ValidationIssue>,
    ) : CampaignValidator {
        val calls = mutableListOf<Pair<Campaign, Set<String>>>()

        override fun validate(
            campaign: Campaign,
            knownRunFunctions: Set<String>,
        ): List<ValidationIssue> = issues.also { calls += campaign to knownRunFunctions }
    }

    @Test
    fun `a valid campaign is returned as loaded and the validator sees the known run functions`() {
        val loaded = campaign(step("s"))
        val requested = mutableListOf<Path>()
        val validator = RecordingValidator(emptyList())
        val source = CampaignSource { path -> loaded.also { requested.add(path) } }

        LoadCampaignUseCase(source, validator).execute(Path.of("any.yaml"), setOf("login")) shouldBe loaded

        requested shouldContainExactly listOf(Path.of("any.yaml"))
        validator.calls shouldContainExactly listOf(loaded to setOf("login"))
    }

    @Test
    fun `any validation issue fails the load with all issues`() {
        val found = listOf(ValidationIssue(3, "first"), ValidationIssue(null, "second"))
        val useCase = LoadCampaignUseCase({ campaign(step("s")) }, RecordingValidator(found))
        val error = shouldThrow<CampaignValidationException> { useCase.execute(Path.of("x.yaml"), emptySet()) }
        error.issues shouldBe found
        error.message shouldContain "line 3: first"
        error.message shouldContain "  - second"
    }
}
