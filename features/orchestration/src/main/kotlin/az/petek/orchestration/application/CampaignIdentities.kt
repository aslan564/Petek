/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
