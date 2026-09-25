/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.campaign.domain.Campaign
import az.petek.core.ids.RunId
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunSummary
import java.nio.file.Path

/**
 * Runs one campaign end to end: plan identities -> start browser -> setup -> steps -> teardown -> finalize.
 * Always tears down in `finally` unless `keepData` (CLAUDE.md rule 8, docs/PLAN.md Faza 5).
 */
interface CampaignRunner {
    suspend fun run(
        campaign: Campaign,
        options: RunOptions = RunOptions(),
    ): RunSummary
}

/** Runs the same campaign N times as one repeat group and reports stability. */
interface RepeatRunner {
    suspend fun repeat(
        campaign: Campaign,
        times: Int,
        keepData: Boolean = false,
    ): List<RunSummary>
}

/** Deletes everything a run created on the target (test company) — also for runs that crashed half-way. */
interface TeardownUseCase {
    suspend fun teardown(runId: RunId?): TeardownResult
}

data class TeardownResult(
    val runId: RunId?,
    val removed: List<String>,
    val failures: List<String>,
)

/** Port implemented by reporting: judge findings and write the report after a run. Returns the report directory. */
fun interface RunFinalizer {
    suspend fun finalize(runId: RunId): Path?
}
