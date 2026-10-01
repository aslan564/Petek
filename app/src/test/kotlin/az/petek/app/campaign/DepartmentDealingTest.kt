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

import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.DepartmentDealing
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.Tenant
import az.petek.core.ids.RunTags
import az.petek.core.model.Role
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.HmacPasswordDeriver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The validator counts a department's testers with [DepartmentDealing] before any identity exists; the registry deals
 * them. Both must agree for every team shape, or a step the validator lets through would match nobody in the run.
 */
class DepartmentDealingTest {
    private val generator = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("secret".toByteArray()))
    private val departments = listOf("IT", "HR", "Satış", "Maliyyə", "Əməliyyat", "Hüquq")

    private fun settings(
        roles: RoleQuota,
        registration: RegistrationQuota,
        departments: List<String>,
        tenant: Tenant = Tenant.COMPANY,
    ) = CampaignSettings(
        target = URI("https://staging.portal.test"),
        testers = roles.total,
        seed = 42,
        names = emptyList(),
        roles = roles,
        departments = departments,
        registration = registration,
        budget = Budget(maxStepsPerAgent = 10, maxMinutes = 5),
        onFail = OnFail.CONTINUE,
        tenant = tenant,
    )

    /** Each role's count in each department: as [DepartmentDealing] reckons it, and as the registry dealt it. */
    private fun counted(settings: CampaignSettings): Pair<Map<Pair<Role, String>, Int>, Map<Pair<Role, String>, Int>> {
        val identities =
            generator.generate(IdentitySpecs.of(settings, "test.portal.example"), RunTags.forPlan("0".repeat(64), 42)).identities
        val cells =
            settings.roles.counts.keys
                .flatMap { role -> settings.departments.map { role to it } }
        return cells.associateWith { (role, department) -> DepartmentDealing.testers(settings, role, department) } to
            cells.associateWith { (role, department) -> identities.count { it.role == role && it.department == department } }
    }

    @Test
    fun `with companies every department holds as many testers of each role as the registry deals it`() {
        for (size in 1..departments.size) {
            for (managers in 0..7) {
                for (employees in 0..12) {
                    if (managers + employees == 0) continue
                    val invite = managers + employees / 2
                    val settings =
                        settings(
                            RoleQuota(admin = 1, manager = managers, employee = employees),
                            RegistrationQuota(invite = invite, companyCode = managers + employees - invite),
                            departments.take(size),
                        )

                    val (reckoned, dealt) = counted(settings)

                    reckoned shouldBe dealt
                }
            }
        }
    }

    @Test
    fun `without companies every department holds as many testers of each role as the registry deals it`() {
        val editor = checkNotNull(Role.fromKey("editor"))
        val reader = checkNotNull(Role.fromKey("reader"))
        for (size in 1..4) {
            for (editors in 0..5) {
                for (readers in 1..7) {
                    val roles = RoleQuota.of(linkedMapOf(editor to editors, reader to readers))
                    val settings =
                        settings(roles, RegistrationQuota.selfSignUp(roles.total), departments.take(size), Tenant.NONE)

                    val (reckoned, dealt) = counted(settings)

                    reckoned shouldBe dealt
                }
            }
        }
    }

    @Test
    fun `a department is named the way the actor resolver matches it, trimmed and ignoring case`() {
        val settings = settings(RoleQuota(1, 3, 6), RegistrationQuota(5, 4), listOf("IT", "HR"))

        DepartmentDealing.testers(settings, Role.MANAGER, " it ") shouldBe 2
        DepartmentDealing.testers(settings, Role.ADMIN, "IT") shouldBe 0
        DepartmentDealing.testers(settings, Role.EMPLOYEE, "Finance") shouldBe 0
    }
}
