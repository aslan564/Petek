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

package az.petek.campaign.domain

import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.campaign.testing.campaign
import az.petek.campaign.testing.companyPortalScenario
import az.petek.campaign.testing.settings
import az.petek.campaign.testing.step
import az.petek.core.model.Role
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class VisitorRunTest {
    private val visitors =
        settings(
            testers = 2,
            roles = RoleQuota(0, 0, 0, mapOf(checkNotNull(Role.fromKey("visitor")) to 2)),
            registration = RegistrationQuota(0, 0, guest = 2),
            departments = emptyList(),
        ).copy(tenant = Tenant.NONE)

    private val gates = step("gates", actor = "visitor[*]", action = StepAction.Run("register_and_login"), phase = StepPhase.SETUP)
    private val health = step("health", actor = "visitor[n=1]", action = StepAction.Run("site_health", mapOf("checks" to "links")))

    @Test
    fun `visitors that only read are a visitor run`() {
        val readOnly =
            step(
                "seen",
                actor = "visitor[n=1]",
                action = StepAction.None,
                assertions = listOf(AssertionSpec.VisibleText("Welcome", 5.seconds), AssertionSpec.HttpStatus("/", "GET", 200)),
            )

        VisitorRun.problems(campaign(health, readOnly, setup = listOf(gates), settings = visitors)).shouldBeEmpty()
    }

    @Test
    fun `an AI step, a function that writes, a check that writes and too many testers are named`() {
        val problems =
            VisitorRun.problems(
                campaign(
                    step("ask", actor = "visitor[n=1]", action = StepAction.Do("Open the contact form and send it")),
                    step("buy", actor = "visitor[n=1]", action = StepAction.Run("register_owner")),
                    step(
                        "approve",
                        actor = "visitor[n=1]",
                        action = StepAction.None,
                        assertions = listOf(AssertionSpec.HttpStatus("/api/x", "POST", 403)),
                    ),
                    setup = listOf(gates),
                    settings = visitors.copy(testers = 5, registration = RegistrationQuota(0, 0, guest = 5)),
                ),
            )

        problems shouldContainAll
            listOf(
                "it asks for 5 testers, a visitor run takes at most 3",
                "step 'ask' is a `do` step, where an AI agent may click and type",
                "step 'buy' runs `register_owner`, which does not only read",
                "step 'approve' checks `http_status`, which writes or needs the site's test API",
            )
    }

    @Test
    fun `testers who sign up or sign in, and companies, are not visitors`() {
        val signingIn = visitors.copy(registration = RegistrationQuota(0, 0, self = 1, guest = 1))

        VisitorRun.problems(campaign(health, settings = signingIn)) shouldBe
            listOf("not every tester is a visitor (registration: {guest: 2})")
        VisitorRun.problems(campaign(health, settings = settings())) shouldContain "it has companies (tenant: company)"
    }

    @Test
    fun `the company portal example is not a visitor run`() {
        val example = YamlCampaignSource().load(companyPortalScenario())

        VisitorRun.problems(example) shouldContain "it has companies (tenant: company)"
    }
}
