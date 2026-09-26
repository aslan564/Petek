/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
