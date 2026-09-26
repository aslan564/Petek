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
