/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

import az.petek.app.campaign.IdentitySpecs
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.StepAction
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.HmacPasswordDeriver
import az.petek.identity.domain.Identity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class RunPlansTest {
    @TempDir
    lateinit var dir: Path

    private fun campaign(): Campaign {
        val file = dir.resolve("plan.yaml")
        Files.writeString(
            file,
            """
            campaign:
              name: plan-demo
              testers: 5
              seed: 3
              roles: {admin: 1, manager: 2, employee: 2}
              departments: [IT, HR]
              registration: {invite: 3, company_code: 1}
              budget: {max_steps_per_agent: 10, max_minutes: 5}
            setup:
              - id: owner
                actor: admin
                run: register_owner
              - id: join
                actor: employee[*] | manager[*]
                run: register_and_login
            steps:
              - id: announce
                actor: admin
                do: "Elan yarat: 'Sabah iclas'"
                emits: announcement_created
                assert:
                  - oracle: {path: "/test/announcements/{last_id}", field: status, equals: published}
              - id: read
                actor: employee[*]
                wait_for: announcement_created
                do: "Elanı oxu"
                assert:
                  - visible_text: {text: "Sabah iclas", within_s: 10}
                  - latency_max: {ms: 5000}
              - id: race
                actor: ["manager[IT]", "manager[HR]"]
                parallel: true
                do: "Eyni ticketi təsdiqlə"
                assert:
                  - only_one_succeeds: true
              - id: forbidden
                actor: employee[dept=IT, n=1]
                do: "Təsdiqləməyə çalış"
                assert:
                  - not_visible: {selector: "[data-testid=approve]"}
                  - http_status: {path: "/api/tickets/1/approve", method: POST, equals: 403}
            """.trimIndent(),
        )
        return YamlCampaignSource(URI("https://kadro.test")).load(file)
    }

    private fun identities(campaign: Campaign): List<Identity> =
        DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))
            .generate(IdentitySpecs.of(campaign.settings, "test.kadro"), RunTags.forPlan(campaign.sourceHash, campaign.settings.seed))
            .identities

    @Test
    fun `every setup and scenario step is planned with the agents its actors resolve to`() {
        val campaign = campaign()

        val plan = RunPlans.of(RunId("run_1"), campaign, identities(campaign).shuffled())

        plan.runId shouldBe RunId("run_1")
        plan.campaignName shouldBe "plan-demo"
        plan.steps.map { it.id } shouldContainExactly listOf("owner", "join", "announce", "read", "race", "forbidden")
        plan.steps.map { it.setup } shouldContainExactly listOf(true, true, false, false, false, false)
        plan.steps[0].agentIds.map { it.value } shouldContainExactly listOf("a01")
        plan.steps[1].agentIds.map { it.value } shouldContainExactly listOf("a02", "a03", "a04", "a05")
        plan.steps[4].agentIds.size shouldBe 2
        plan.steps[5].agentIds.size shouldBe 1
    }

    @Test
    fun `each step says what it does, which events it emits or waits for and how it is checked`() {
        val plan = RunPlans.of(null, campaign(), emptyList())

        val owner = plan.steps[0]
        owner.kind shouldBe "run"
        owner.action shouldBe "register_owner"
        owner.agentIds shouldBe emptyList()
        val announce = plan.steps[2]
        announce.kind shouldBe "do"
        announce.action shouldBe "Elan yarat: 'Sabah iclas'"
        announce.emits shouldBe "announcement_created"
        announce.assertions shouldContainExactly listOf("oracle /test/announcements/{last_id} status = 'published'")
        plan.steps[3].waitFor shouldBe "announcement_created"
        plan.steps[3].assertions shouldContainExactly listOf("visible_text 'Sabah iclas' within 10s", "latency_max 5s")
        plan.steps[4].parallel shouldBe true
        plan.steps[4].assertions shouldContainExactly listOf("only_one_succeeds")
        plan.steps[5].assertions shouldContainExactly
            listOf("not_visible [data-testid=approve]", "http_status POST /api/tickets/1/approve = 403")
    }

    @Test
    fun `actions and the remaining assertions have a readable line`() {
        RunPlans.describe(StepAction.Run("seed_company", mapOf("departments" to "IT"))) shouldBe "seed_company(departments=IT)"
        RunPlans.describe(StepAction.None) shouldBe "yalnız gözləyir və yoxlayır"
        RunPlans.describe(AssertionSpec.Count("[data-testid=item]", 1)) shouldBe "count [data-testid=item] = 1"
        RunPlans.describe(AssertionSpec.NotVisible("Təsdiqlə", null)) shouldBe "not_visible 'Təsdiqlə'"
        RunPlans.describe(AssertionSpec.Oracle("/test/x", null, null, "a@b")) shouldBe "oracle /test/x contains 'a@b'"
        RunPlans.describe(AssertionSpec.VisibleText("x", 2.seconds)) shouldBe "visible_text 'x' within 2s"
    }
}
