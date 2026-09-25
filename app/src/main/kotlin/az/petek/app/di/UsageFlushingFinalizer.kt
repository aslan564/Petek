/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.di

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.UsageRecord
import az.petek.llm.application.UsageMeter
import az.petek.llm.application.UsageTotals
import az.petek.orchestration.application.RunFinalizer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * The [RunFinalizer] the runner calls after a run: first it moves the LLM usage the [meter] collected during the run
 * into the evidence store (one [UsageRecord] per agent), then it lets [delegate] judge and write the report, so the
 * report's token and cost figures include the whole run.
 *
 * The meter is drained, so each run (also each run of `--repeat`) records only its own calls. Labels that are not an
 * agent id (e.g. a health check) are not attributed to the run. A usage record that cannot be stored is logged and
 * skipped: the report is still written, just without that agent's usage.
 */
class UsageFlushingFinalizer(
    private val meter: UsageMeter,
    private val recorder: EvidenceRecorder,
    private val delegate: RunFinalizer,
) : RunFinalizer {
    override suspend fun finalize(runId: RunId): Path? {
        flush(runId)
        return delegate.finalize(runId)
    }

    private suspend fun flush(runId: RunId) {
        for ((key, totals) in meter.drain()) {
            val agentId = agentIdOf(key)
            if (agentId == null) {
                logger.debug { "run $runId: LLM usage of '$key' is not an agent's and is not recorded" }
                continue
            }
            try {
                recorder.usage(totals.toRecord(runId, agentId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "run $runId: the LLM usage of $agentId could not be recorded" }
            }
        }
    }

    private fun agentIdOf(key: String): AgentId? = if (AGENT_ID.matches(key)) AgentId(key) else null

    private fun UsageTotals.toRecord(
        runId: RunId,
        agentId: AgentId,
    ) = UsageRecord(
        runId = runId,
        agentId = agentId,
        inputTokens = tokens.inputTokens,
        outputTokens = tokens.outputTokens,
        cacheReadTokens = tokens.cacheReadTokens,
        costUsd = costUsd,
        // Every request the agent made, answered or not: failed calls cost time and quota too.
        calls = (calls + failedCalls).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )

    private companion object {
        val AGENT_ID = Regex("a\\d{2,3}")
    }
}
