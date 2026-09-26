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

package az.petek.campaign.application

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.CampaignValidator
import java.nio.file.Path

/** Port: reads a campaign file into the domain model. Syntax errors carry file line numbers. */
fun interface CampaignSource {
    fun load(path: Path): Campaign
}

/** Loads and validates a campaign; the result is safe to execute. */
class LoadCampaignUseCase(
    private val source: CampaignSource,
    private val validator: CampaignValidator,
) {
    fun execute(
        path: Path,
        knownRunFunctions: Set<String>,
    ): Campaign {
        val campaign = source.load(path)
        val issues = validator.validate(campaign, knownRunFunctions)
        if (issues.isNotEmpty()) throw CampaignValidationException(issues)
        return campaign
    }
}
