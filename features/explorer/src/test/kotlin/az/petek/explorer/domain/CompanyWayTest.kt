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

package az.petek.explorer.domain

import az.petek.campaign.domain.Tenant
import az.petek.explorer.support.Models
import az.petek.explorer.support.Models.action
import az.petek.explorer.support.Models.field
import az.petek.explorer.support.Models.form
import az.petek.explorer.support.Models.page
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A site's company model is read from its gate, never from its test API (Faza 25.1). */
class CompanyWayTest {
    private val joinPage = page("/join", form(ActionKind.REGISTER, "join-submit", "/join", field("company_code", required = true)))
    private val invite =
        action("company-invite", ActionKind.CREATE, "/company", name = "Dəvət et", allowed = setOf("admin"), httpPath = "/company/invites")

    private fun site(
        pages: List<PageModel>,
        actions: List<ActionModel>,
    ) = Models.model(pages + page("/login", form(ActionKind.LOGIN, "login-submit", "/login", field("email", "email"))), actions)

    @Test
    fun `a join form and an operation that hands out invitations make a company way`() {
        val way = GateMaps.companyWay(site(listOf(joinPage, page("/company")), listOf(invite))).shouldNotBeNull()

        way.joinPages shouldBe listOf("/join")
        way.issuedBy shouldBe listOf("company-invite")
    }

    @Test
    fun `a join form without anyone handing out codes, or invitations nobody joins with, is no company way`() {
        GateMaps.companyWay(site(listOf(joinPage), emptyList())).shouldBeNull()
        GateMaps.companyWay(site(listOf(page("/company")), listOf(invite))).shouldBeNull()
        // A visitor's "invite a friend" button is not a role handing out company codes.
        GateMaps.companyWay(site(listOf(joinPage), listOf(invite.copy(allowedRoles = setOf("anonymous"))))).shouldBeNull()
    }

    @Test
    fun `a test API alone never makes a site one with companies`() {
        val plain =
            site(listOf(page("/register", form(ActionKind.REGISTER, "register-submit", "/register", field("email", "email")))), emptyList())

        GateMaps.tenantFor(plain, owner = null, testApi = true) shouldBe Tenant.NONE
    }

    @Test
    fun `companies are chosen when the explorer saw the site's own way and the test API can seed one`() {
        val company = site(listOf(joinPage, page("/company")), listOf(invite))

        GateMaps.tenantFor(company, owner = null, testApi = true) shouldBe Tenant.COMPANY
        // The draft's setup seeds the test company through the test API: without it there is no company to join.
        GateMaps.tenantFor(company, owner = null, testApi = false) shouldBe Tenant.NONE
    }

    @Test
    fun `the owner's word wins over what the explorer saw`() {
        val company = site(listOf(joinPage, page("/company")), listOf(invite))
        val plain = site(emptyList(), emptyList())

        GateMaps.tenantFor(company, owner = Tenant.NONE, testApi = true) shouldBe Tenant.NONE
        GateMaps.tenantFor(plain, owner = Tenant.COMPANY, testApi = false) shouldBe Tenant.COMPANY
    }
}
