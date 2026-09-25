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
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.Flow
import az.petek.campaign.domain.FlowNames
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.LinkPurpose
import az.petek.campaign.domain.Pacing
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.ValueTarget
import az.petek.campaign.domain.WaitForSpec
import az.petek.campaign.testing.KNOWN_RUN_FUNCTIONS
import az.petek.campaign.testing.kadrohrScenario
import az.petek.core.model.Role
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `scenarios/kadrohr.yaml`, the campaign for the real KadroHR, describes its flows and validates for any team size. */
class KadrohrCampaignFileTest {
    private val file = kadrohrScenario()
    private val campaign = YamlCampaignSource().load(file)
    private val target = campaign.target

    private fun step(id: String) = campaign.allSteps.single { it.id == id }

    private fun flow(name: String): Flow = target.flows.getValue(name)

    private fun allSteps(steps: List<FlowStep>): List<FlowStep> =
        steps.flatMap { step ->
            when (step) {
                is FlowStep.IfVisible -> listOf(step) + allSteps(step.then)
                is FlowStep.Journey -> listOf(step) + step.pages.flatMap { allSteps(it.steps) }
                else -> listOf(step)
            }
        }

    @Test
    fun `the real campaign validates without issues`() {
        DefaultCampaignValidator().validate(campaign, KNOWN_RUN_FUNCTIONS).shouldBeEmpty()
    }

    @Test
    fun `the file is plain YAML that needs no repair of its actor lists`() {
        val text = Files.readString(file)
        quoteActorFlowLists(text) shouldBe text
    }

    @Test
    fun `it validates for any number of testers that leaves two managers for the race`() {
        listOf(5, 6, 8, 12, 20, 30, 50, 100, 500).forEach { testers ->
            val nonAdmins = testers - 1
            val managers = maxOf(2, (nonAdmins * 5 / 29.0).roundToInt())
            val invite = maxOf(managers, (nonAdmins + 1) / 2)
            val scaled =
                campaign.copy(
                    settings =
                        campaign.settings.copy(
                            testers = testers,
                            roles = RoleQuota(admin = 1, manager = managers, employee = nonAdmins - managers),
                            registration = RegistrationQuota(invite = invite, companyCode = nonAdmins - invite),
                        ),
                )

            val issues = DefaultCampaignValidator().validate(scaled, KNOWN_RUN_FUNCTIONS)

            check(issues.isEmpty()) { "$testers testers: $issues" }
        }
    }

    @Test
    fun `the real site is targeted at a paced rate with KadroHR's API prefix and first-visit flags`() {
        campaign.settings.name shouldBe "kadrohr-real"
        campaign.settings.target shouldBe URI("https://kadrohr.com")
        campaign.settings.testers shouldBe 30
        campaign.settings.pacing shouldBe Pacing(startStagger = 1500.milliseconds)
        target.apiPrefix shouldBe "/api/v1"
        target.localStorage["kadro:domain_dialog_dismissed"] shouldBe "1"
        target.localStorage["kadro:lang"] shouldBe "az"
        target.dismiss shouldContainExactly listOf("role=button[name=\"Qəbul edirəm\"]")
    }

    @Test
    fun `every run function has a KadroHR flow and the password is only ever typed`() {
        target.flows.keys shouldBe FlowNames.ALL
        listOf(FlowNames.REGISTER_OWNER, FlowNames.JOIN_BY_INVITE, FlowNames.JOIN_BY_CODE, FlowNames.LOGIN).forEach { name ->
            flow(name) shouldNotBe TargetProfile.DEFAULT_FLOWS[name]
        }
        flow(FlowNames.VERIFY_IDENTITY) shouldBe TargetProfile.DEFAULT_FLOWS[FlowNames.VERIFY_IDENTITY]
        val typed =
            target.flows.values
                .flatMap { allSteps(it.steps) }
                .filter { "{self.password}" in it.toString() }
        typed.all { it is FlowStep.Fill } shouldBe true
        typed.size shouldBe 7
    }

    @Test
    fun `the owner confirms the sign-up by link and the login needs the company code`() {
        val signUp = flow(FlowNames.REGISTER_OWNER).steps
        signUp shouldContain FlowStep.Fill("register.first_name", "{self.first_name}")
        signUp shouldContain FlowStep.Fill("register.last_name", "{self.last_name}")
        signUp shouldContain FlowStep.Fill("register.confirm_password", "{self.password}")
        signUp shouldContain FlowStep.Check("register.mode_hybrid")
        signUp shouldContain FlowStep.EmailLink(LinkPurpose.VERIFY, "registration/verify\\?token=")
        signUp shouldContain FlowStep.AccountCreated
        flow(FlowNames.LOGIN).steps shouldContain FlowStep.Fill("login.company_code", "{shared.company_code}")
        flow(FlowNames.LOGIN).steps.last() shouldBe FlowStep.SaveSession
    }

    @Test
    fun `employees join by invitation link or with the company code`() {
        flow(FlowNames.JOIN_BY_INVITE).steps.first() shouldBe FlowStep.EmailLink(LinkPurpose.INVITE, "set-password\\?token=")
        flow(FlowNames.JOIN_BY_CODE).steps.first() shouldBe FlowStep.Goto("/register/employee")
        flow(FlowNames.JOIN_BY_CODE).steps shouldContain FlowStep.Fill("join.code", "{shared.company_code}")
        listOf(FlowNames.JOIN_BY_INVITE, FlowNames.JOIN_BY_CODE).forEach { flow(it).steps shouldContain FlowStep.AccountCreated }
        flow(FlowNames.JOIN_BY_CODE)
            .steps
            .filterIsInstance<FlowStep.EmailLink>()
            .single()
            .target shouldBe ValueTarget.vars("verify_link")
    }

    @Test
    fun `setup runs the flows deterministically and the steps cover announcements and a leave approval race`() {
        campaign.setup.map { it.id } shouldContainExactly listOf("owner_signup", "seed", "join")
        step("owner_signup").action shouldBe StepAction.Run("register_owner", mapOf("company" to "Pətək Test MMC"))
        step("join").action shouldBe StepAction.Run("register_and_login")
        campaign.steps.map { it.id } shouldContainExactly
            listOf("announce", "read_announce", "announce_seen", "leave_request", "leave_race", "forbidden_approval")
        step("read_announce").waitFor shouldBe WaitForSpec("announcement_created", 60.seconds)
    }

    @Test
    fun `two managers race to approve the same leave request and an employee may not`() {
        val race = step("leave_race")
        race.parallel shouldBe true
        race.waitFor shouldBe WaitForSpec("leave_request_created", 30.seconds)
        race.actors.selectors shouldContainExactly
            listOf(ActorSelector(Role.MANAGER, department = "IT"), ActorSelector(Role.MANAGER, department = "HR"))
        race.assertions shouldContainAll
            listOf(
                AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", ".*/leave-requests/[^/]+/approve")),
                AssertionSpec.Oracle("/test/leave-requests/{last_id}", "status", "APPROVED", null),
            )
        step("forbidden_approval").assertions shouldContainExactly
            listOf(
                AssertionSpec.NotVisible(null, "role=button[name=\"Təsdiqlə\"]"),
                AssertionSpec.HttpStatus("/api/v1/leave-requests/{last_id}/approve", "POST", 403),
            )
    }

    @Test
    fun `created objects are identified through KadroHR's test API`() {
        target.idSources shouldBe
            mapOf(
                "announcement_created" to IdSource.OracleField("/test/announcements/latest?by={self.email}", "id"),
                "leave_request_created" to IdSource.OracleField("/test/leave-requests/latest?by={self.email}", "id"),
            )
    }
}
