/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.infrastructure

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.Tenant
import az.petek.core.model.Role
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TenantlessCampaignTest {
    @TempDir
    lateinit var dir: Path

    private fun load(yaml: String): Campaign =
        YamlCampaignSource().load(dir.resolve("site.yaml").also { Files.writeString(it, yaml.trimIndent()) })

    private fun campaign(
        roles: String = "{editor: 1, reader: 2}",
        registration: String? = null,
        steps: String = "  - actor: editor\n    do: write a post\n  - actor: reader[reg=self]\n    do: read it",
    ) = listOfNotNull(
        "campaign:",
        "  target: https://blog.example.test",
        "  tenant: none",
        "  testers: 3",
        "  seed: 1",
        "  roles: $roles",
        registration,
        "  budget: {max_steps_per_agent: 10, max_minutes: 5}",
        "steps:",
        steps,
    ).joinToString("\n")

    private fun issues(campaign: Campaign) = DefaultCampaignValidator().validate(campaign, setOf("register_and_login", "seed_company"))

    @Test
    fun `a site without companies names its own roles and needs no departments`() {
        val loaded = load(campaign())

        loaded.settings.tenant shouldBe Tenant.NONE
        loaded.settings.roles.roles shouldContainExactly listOf(checkNotNull(Role.fromKey("editor")), checkNotNull(Role.fromKey("reader")))
        loaded.settings.departments.shouldBeEmpty()
        loaded.settings.registration shouldBe RegistrationQuota.selfSignUp(3)
        issues(loaded).shouldBeEmpty()
    }

    @Test
    fun `the gates are read and must add up to the testers`() {
        val loaded = load(campaign(registration = "  registration: {self: 1, login: 1, guest: 0}"))

        loaded.settings.registration shouldBe RegistrationQuota(0, 0, self = 1, login = 1, guest = 0)
        issues(loaded).single().message shouldContain "registration adds up to 2 (self 1 + login 1 + guest 0) but campaign.testers is 3"
    }

    @Test
    fun `an actor of a role the campaign does not have matches nobody`() {
        val loaded = load(campaign(steps = "  - actor: admin\n    do: x"))

        issues(loaded).single().message shouldContain "admin"
    }

    @Test
    fun `company run functions are refused without companies`() {
        val loaded = load(campaign(steps = "  - actor: editor\n    run: seed_company"))

        issues(loaded).single().message shouldContain "creates a company, but campaign.tenant is none"
    }

    @Test
    fun `a role key that cannot be a role is reported with its line`() {
        val issue =
            shouldThrow<CampaignValidationException> { load(campaign(roles = "{Editor!: 3}")) }.issues.single {
                "not a role name" in
                    it.message
            }

        issue.line shouldBe 6
    }

    @Test
    fun `a company campaign still allows only the company roles`() {
        val loaded =
            load(
                """
                campaign:
                  target: https://staging.kadrohr.test
                  testers: 2
                  seed: 1
                  roles: {admin: 1, manager: 0, employee: 1}
                  departments: [IT]
                  budget: {max_steps_per_agent: 10, max_minutes: 5}
                steps:
                  - actor: editor
                    do: x
                """,
            )

        loaded.settings.tenant shouldBe Tenant.COMPANY
        issues(loaded).single().message shouldContain "editor"
    }
}
