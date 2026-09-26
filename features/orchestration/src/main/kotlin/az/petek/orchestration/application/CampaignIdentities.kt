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

package az.petek.orchestration.application

import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.Tenant
import az.petek.core.model.RegistrationMode
import az.petek.identity.domain.GivenAccount
import az.petek.identity.domain.IdentitySpec

/** Maps a campaign's quotas to the identity generator's input; the runner and `petek plan` use the same mapping. */
object CampaignIdentities {
    fun spec(
        settings: CampaignSettings,
        mailDomain: String,
        mailbox: String? = null,
        accounts: List<GivenAccount> = emptyList(),
    ): IdentitySpec =
        IdentitySpec(
            testers = settings.testers,
            seed = settings.seed,
            names = settings.names,
            admins = settings.roles.admin,
            managers = settings.roles.manager,
            employees = settings.roles.employee,
            departments = settings.departments,
            inviteCount = settings.registration.invite,
            companyCodeCount = settings.registration.companyCode,
            mailDomain = mailDomain,
            mailbox = mailbox,
            companies = settings.tenant == Tenant.COMPANY,
            ownRoles = if (settings.tenant == Tenant.NONE) settings.roles.counts.filterValues { it != 0 } else emptyMap(),
            gates = RegistrationMode.GATES.associateWith(settings.registration::count),
            accounts = accounts,
        )
}
