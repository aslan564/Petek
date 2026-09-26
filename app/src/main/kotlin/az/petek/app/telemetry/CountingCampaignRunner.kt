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

package az.petek.app.telemetry

import az.petek.campaign.domain.Campaign
import az.petek.core.telemetry.UsageSink
import az.petek.orchestration.application.CampaignRunner
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunSummary

/** Counts every campaign run started, by AI provider kind and tester-count bucket (never the target or content). */
class CountingCampaignRunner(
    private val delegate: CampaignRunner,
    private val sink: UsageSink,
    private val provider: String,
) : CampaignRunner {
    override suspend fun run(
        campaign: Campaign,
        options: RunOptions,
    ): RunSummary {
        sink.count("run.started", 1, UsageSink.Tags.of("provider" to provider, "testers" to bucket(campaign.settings.testers)))
        return delegate.run(campaign, options)
    }

    companion object {
        /** `1`, `2-10`, `11-30`, `31-100`, `101+`: enough to see how Pətək is used, never an exact figure. */
        fun bucket(testers: Int): String =
            when {
                testers <= 1 -> "1"
                testers <= 10 -> "2-10"
                testers <= 30 -> "11-30"
                testers <= 100 -> "31-100"
                else -> "101+"
            }
    }
}
