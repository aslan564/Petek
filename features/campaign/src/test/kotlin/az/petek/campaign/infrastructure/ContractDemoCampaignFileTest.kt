/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.infrastructure

import az.petek.campaign.domain.ActorSelector
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.Pacing
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.WaitForSpec
import az.petek.campaign.testing.KNOWN_RUN_FUNCTIONS
import az.petek.campaign.testing.contractDemoScenario
import az.petek.campaign.testing.repoFile
import az.petek.core.model.Role
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `scenarios/contract-demo.yaml`, the campaign for the contract site (the fake target), must load completely and
 * validate without a single issue.
 */
class ContractDemoCampaignFileTest {
    private val file = contractDemoScenario()
    private val campaign = YamlCampaignSource().load(file)
    private val fileLines = Files.readAllLines(file)

    private fun lineContaining(fragment: String): Int = fileLines.indexOfFirst { fragment in it }.also { check(it >= 0) { fragment } } + 1

    private fun step(id: String) = campaign.allSteps.single { it.id == id }

    @Test
    fun `the contract demo campaign validates without issues`() {
        DefaultCampaignValidator().validate(campaign, KNOWN_RUN_FUNCTIONS).shouldBeEmpty()
    }

    @Test
    fun `the file is plain YAML that needs no repair of its actor lists`() {
        val text = Files.readString(file)
        quoteActorFlowLists(text) shouldBe text
    }

    @Test
    fun `the scenario example of docs PLAN is exactly this file`() {
        val section = Files.readString(repoFile("docs/PLAN.md")).substringAfter("## Ssenari formatı")
        val example = section.substringAfter("```yaml\n").substringBefore("```")
        example shouldBe Files.readString(file)
        section.substringBefore("```yaml") shouldContain "`scenarios/contract-demo.yaml`"
    }

    @Test
    fun `every manager can be invited, so no manager has to join with the company code`() {
        val settings = campaign.settings
        (settings.registration.invite >= settings.roles.manager) shouldBe true
        settings.registration.invite + settings.registration.companyCode shouldBe settings.roles.manager + settings.roles.employee
    }

    @Test
    fun `settings are read as written`() {
        val settings = campaign.settings
        settings.name shouldBe "contract-demo"
        settings.target shouldBe URI("https://staging.kadrohr.com")
        settings.testers shouldBe 30
        settings.seed shouldBe 42L
        settings.names shouldContainExactly listOf("Əli", "Vəli", "Sahil", "Cəmil", "Amil")
        settings.roles shouldBe RoleQuota(admin = 1, manager = 5, employee = 24)
        settings.departments shouldContainExactly listOf("IT", "HR", "Satış", "Maliyyə", "Əməliyyat")
        settings.registration shouldBe RegistrationQuota(invite = 15, companyCode = 14)
        settings.budget shouldBe Budget(maxStepsPerAgent = 60, maxMinutes = 40)
        settings.onFail shouldBe OnFail.CONTINUE
    }

    @Test
    fun `setup and steps keep their order, ids and phases`() {
        campaign.setup.map { it.id } shouldContainExactly listOf("owner_signup", "seed", "join")
        campaign.steps.map { it.id } shouldContainExactly listOf("announce", "read_announce", "ticket", "ticket_flow", "race", "forbidden")
        campaign.setup.map { it.phase }.distinct() shouldContainExactly listOf(StepPhase.SETUP)
        campaign.steps.map { it.phase }.distinct() shouldContainExactly listOf(StepPhase.MAIN)
    }

    @Test
    fun `setup actions mix natural language and run functions`() {
        step("owner_signup").action shouldBe
            StepAction.Do("Qeydiyyatdan keç, email kodunu və istənsə telefon kodunu təsdiqlə, 'Pətək Test MMC' adlı şirkət yarat")
        step("seed").action shouldBe StepAction.Run("seed_company")
        step("join").action shouldBe StepAction.Run("register_and_login")
        step("join").actors.selectors shouldContainExactly listOf(ActorSelector(Role.EMPLOYEE), ActorSelector(Role.MANAGER))
    }

    @Test
    fun `the announcement flow emits, waits and asserts`() {
        val announce = step("announce")
        announce.emits shouldBe EmitSpec("announcement_created", null)
        announce.assertions shouldContainExactly
            listOf(AssertionSpec.Oracle("/test/announcements/{last_id}", "status", "published", null))

        val read = step("read_announce")
        read.actors.selectors shouldContainExactly listOf(ActorSelector(Role.EMPLOYEE))
        read.waitFor shouldBe WaitForSpec("announcement_created", 30.seconds)
        read.assertions shouldContainExactly
            listOf(
                AssertionSpec.VisibleText("Sabah 10:00 ümumi iclas", 5.seconds),
                AssertionSpec.LatencyMax(5000.milliseconds),
                AssertionSpec.Oracle("/test/announcements/{last_id}/receipts", null, null, "{self.email}"),
            )
    }

    @Test
    fun `the ticket flow uses department and position selectors`() {
        step("ticket").actors.selectors shouldContainExactly listOf(ActorSelector(Role.EMPLOYEE, department = "IT", nth = 1))
        step("ticket").emits shouldBe EmitSpec("ticket_created", null)
        step("ticket_flow").actors.selectors shouldContainExactly listOf(ActorSelector(Role.MANAGER, department = "IT"))
        step("ticket_flow").waitFor shouldBe WaitForSpec("ticket_created", 30.seconds)
    }

    @Test
    fun `the race runs two managers in parallel`() {
        val race = step("race")
        race.parallel shouldBe true
        race.actors.selectors shouldContainExactly
            listOf(ActorSelector(Role.MANAGER, department = "IT"), ActorSelector(Role.MANAGER, department = "HR"))
        race.assertions shouldContainExactly listOf(AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", ".*/approve")))
    }

    @Test
    fun `the forbidden step checks the UI and the API`() {
        step("forbidden").assertions shouldContainExactly
            listOf(
                AssertionSpec.NotVisible(null, "[data-testid=\"ticket-approve\"]"),
                AssertionSpec.HttpStatus("/api/tickets/{last_id}/approve", "POST", 403),
            )
    }

    @Test
    fun `id sources come from the oracle`() {
        campaign.target.idSources shouldBe
            mapOf(
                "announcement_created" to IdSource.OracleField("/test/announcements/latest?by={self.email}", "id"),
                "ticket_created" to IdSource.OracleField("/test/tickets/latest?by={self.email}", "id"),
            )
        campaign.target.paths shouldBe emptyMap()
        campaign.target.selectors shouldBe emptyMap()
        campaign.target.flows shouldBe TargetProfile.DEFAULT_FLOWS
        campaign.target.apiPrefix shouldBe TargetProfile.DEFAULT_API_PREFIX
        campaign.settings.pacing shouldBe Pacing.NONE
    }

    @Test
    fun `steps and settings remember their lines in the file`() {
        step("owner_signup").line shouldBe lineContaining("- id: owner_signup")
        step("forbidden").line shouldBe lineContaining("- id: forbidden")
        campaign.sourceLines.lineOf("campaign.testers") shouldBe lineContaining("testers: 30")
        campaign.sourceLines.lineOf("steps[1].assert[1]") shouldBe lineContaining("latency_max")
        campaign.sourceLines.lineOf("target_profile.id_sources.ticket_created") shouldBe lineContaining("ticket_created:")
    }

    @Test
    fun `the source hash is the SHA-256 of the file bytes`() {
        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
        campaign.sourceHash shouldBe expected
        campaign.sourceHash shouldBe campaign.sourceHash.lowercase()
        campaign.sourceHash.length shouldBe 64
    }
}
