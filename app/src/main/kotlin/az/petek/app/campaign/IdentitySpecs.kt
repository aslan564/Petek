/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.campaign

import az.petek.campaign.domain.CampaignSettings
import az.petek.identity.domain.GivenAccount
import az.petek.identity.domain.IdentitySpec
import az.petek.orchestration.application.CampaignIdentities

/** Maps campaign quotas to the identity generator's input the same way the runner does, so `plan` shows who runs. */
object IdentitySpecs {
    fun of(
        settings: CampaignSettings,
        mailDomain: String,
        mailbox: String? = null,
        accounts: List<GivenAccount> = emptyList(),
    ): IdentitySpec = CampaignIdentities.spec(settings, mailDomain, mailbox, accounts)
}
