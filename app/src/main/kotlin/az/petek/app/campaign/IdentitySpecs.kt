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
