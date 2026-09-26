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

package az.petek.scenarios.infrastructure

import az.petek.campaign.application.CampaignSource
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.CampaignValidator
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.ValidationIssue
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.testing.ScenarioTestKit
import az.petek.scenarios.testing.ScenarioTestKit.MINI_SHA
import az.petek.scenarios.testing.ScenarioTestKit.MINI_YAML
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

class CampaignScenarioValidatorTest {
    @TempDir
    lateinit var dir: Path

    private val validator get() = ScenarioTestKit.validator(workDirectory = dir.resolve("work"))

    @Test
    fun `a valid scenario loads into its campaign with no issues`() =
        runTest {
            val check = validator.check(MINI_YAML, "mini.yaml")

            check.valid shouldBe true
            check.issues.shouldBeEmpty()
            val campaign = check.validCampaign()
            campaign.settings.name shouldBe "mini"
            campaign.allSteps.map { it.id } shouldBe listOf("join", "announce", "read_announce", "race", "forbidden")
        }

    @Test
    fun `the loaded campaign hashes like the stored text so runs link back to the version`() =
        runTest {
            validator
                .check(MINI_YAML, "mini.yaml")
                .campaign
                .shouldNotBeNull()
                .sourceHash shouldBe MINI_SHA
        }

    @Test
    fun `a YAML syntax error is an issue with its line and no campaign`() =
        runTest {
            val check = validator.check("campaign:\n  name: [unclosed\n", "broken.yaml")

            check.campaign.shouldBeNull()
            check.valid shouldBe false
            check.issues
                .single()
                .line
                .shouldNotBeNull()
            shouldThrow<ScenarioInvalidException> { check.validCampaign() }
        }

    @Test
    fun `a cross-field rule violation keeps the campaign but lists the issue with its line`() =
        runTest {
            val broken = MINI_YAML.replace("wait_for: announcement_created", "wait_for: ticket_created")

            val check = validator.check(broken, "mini.yaml")

            check.campaign.shouldNotBeNull()
            check.valid shouldBe false
            check.issues.single().message shouldContain "ticket_created"
            check.issues.single().line shouldBe
                broken.lines().indexOfFirst { it.contains("wait_for: ticket_created") } + 1
        }

    @Test
    fun `an unknown run function is an issue`() =
        runTest {
            val check = ScenarioTestKit.validator(dir, runFunctions = setOf("login")).check(MINI_YAML, "mini.yaml")

            check.valid shouldBe false
            check.issues.map { it.message }.single() shouldContain "register_and_login"
        }

    @Test
    fun `a text without campaign name is named after the checked file name`() =
        runTest {
            val unnamed = MINI_YAML.replace("  name: mini\n", "")

            validator
                .check(unnamed, "portal-core.yaml")
                .validCampaign()
                .settings.name shouldBe "portal-core"
        }

    @Test
    fun `temporary files are removed after every check`() =
        runTest {
            validator.check(MINI_YAML, "mini.yaml")
            validator.check("not: [valid", "broken.yaml")

            dir.resolve("work").listDirectoryEntries().shouldBeEmpty()
        }

    @Test
    fun `a file name cannot escape the private directory`() =
        runTest {
            var seen: Path? = null
            val spy =
                CampaignSource { path ->
                    seen = path
                    throw CampaignValidationException(listOf(ValidationIssue(null, "stop")))
                }
            CampaignScenarioValidator(spy, DefaultCampaignValidator(), emptySet(), dir).check(MINI_YAML, "../../etc/passwd")

            val file = seen.shouldNotBeNull()
            file.fileName.toString() shouldBe "passwd"
            file.parent.parent shouldBe dir
            CampaignScenarioValidator.safeFileName("..") shouldBe "scenario.yaml"
            CampaignScenarioValidator.safeFileName("a b?.yaml") shouldBe "a_b_.yaml"
            CampaignScenarioValidator.safeFileName("Pətək.yaml") shouldBe "Pətək.yaml"
        }

    @Test
    fun `the text is written byte exact including a byte order mark and non-ASCII letters`() =
        runTest {
            var bytes: ByteArray? = null
            val spy =
                CampaignSource { path ->
                    bytes = Files.readAllBytes(path)
                    YamlCampaignSource().load(path)
                }
            val text = "\uFEFF" + MINI_YAML

            CampaignScenarioValidator(spy, DefaultCampaignValidator(), ScenarioTestKit.RUN_FUNCTIONS, dir).check(text, "mini.yaml")

            bytes.shouldNotBeNull().toList() shouldBe text.toByteArray(Charsets.UTF_8).toList()
        }

    @Test
    fun `the default work directory is the system temporary directory`() =
        runTest {
            ScenarioTestKit
                .validator(workDirectory = null)
                .check(MINI_YAML, "mini.yaml")
                .issues
                .shouldBeEmpty()
            ScenarioTestKit
                .validator(workDirectory = null)
                .check("x: [", "x.yaml")
                .issues
                .shouldNotBeEmpty()
        }

    @Test
    fun `absurdly nested text is an issue, never a crash`() =
        runTest {
            val check = validator.check("x: " + "[".repeat(5_000) + "]".repeat(5_000) + "\n", "deep.yaml")

            check.campaign.shouldBeNull()
            check.issues.single().message shouldContain "nested too deeply"
            dir.resolve("work").listDirectoryEntries().shouldBeEmpty()
        }

    @Test
    fun `a loader or validator that crashes on the text yields an issue`() =
        runTest {
            val crashingLoader = CampaignSource { error("mapper bug") }
            val loaded = ScenarioTestKit.MINI_CAMPAIGN
            val crashingValidator =
                object : CampaignValidator {
                    override fun validate(
                        campaign: Campaign,
                        knownRunFunctions: Set<String>,
                    ): List<ValidationIssue> = throw IllegalArgumentException("validator bug")
                }

            CampaignScenarioValidator(crashingLoader, DefaultCampaignValidator(), emptySet(), dir)
                .check(MINI_YAML, "mini.yaml")
                .issues
                .single()
                .message shouldBe "the campaign loader failed on this text: IllegalStateException: mapper bug"
            val check =
                CampaignScenarioValidator(
                    CampaignSource { loaded },
                    crashingValidator,
                    emptySet(),
                    dir,
                ).check(MINI_YAML, "mini.yaml")
            check.valid shouldBe false
            check.issues.single().message shouldContain "the campaign validator failed on this text: IllegalArgumentException"
        }
}
