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

package az.petek.agent.application

import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.StepContext
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Writes the evidence of agent actions (CLAUDE.md rules 1, 4, 5): one [StepRecord] per action with harness
 * timings, then the page artifacts linked to it. The step is recorded before its artifacts so every artifact
 * points at an existing step. A failing screenshot never fails the action it documents; it is logged instead.
 */
internal class StepEvidence(
    private val recorder: EvidenceRecorder,
    private val artifacts: ArtifactStore,
    private val clock: HarnessClock,
    private val ids: IdGenerator,
) {
    fun now(): HarnessTimestamp = clock.now()

    suspend fun record(
        runtime: AgentRuntime,
        step: StepContext,
        kind: StepKind,
        action: String,
        reason: String?,
        startedAt: HarnessTimestamp,
        status: StepStatus,
        detail: String?,
    ): StepId {
        val endedAt = clock.now()
        val stepId = ids.stepId()
        recorder.step(
            StepRecord(
                stepId = stepId,
                runId = runtime.runId,
                agentId = runtime.identity.agentId,
                scenarioStep = step.scenarioStep,
                kind = kind,
                action = runtime.redact(action).clip(MAX_ACTION_CHARS),
                llmReason = reason?.let { runtime.redact(it).clip(MAX_DETAIL_CHARS) },
                startedAt = startedAt.wall,
                endedAt = endedAt.wall,
                durationMs = startedAt.elapsedUntil(endedAt).inWholeMilliseconds.coerceAtLeast(0),
                status = status,
                detail = detail?.let { runtime.redact(it).clip(MAX_DETAIL_CHARS) },
                correlationId = step.correlationId,
            ),
        )
        return stepId
    }

    /**
     * Captures the page after [stepId]: a screenshot when [screenshot], the accessibility tree when [accessibility].
     * Bounded in time so a hung browser cannot stall the run while evidence is collected.
     */
    suspend fun capture(
        runtime: AgentRuntime,
        stepId: StepId,
        screenshot: Boolean,
        accessibility: Boolean,
    ) {
        if (screenshot) attach(runtime, stepId, ArtifactType.SCREENSHOT) { runtime.session.screenshot() }
        if (accessibility) {
            attach(runtime, stepId, ArtifactType.A11Y) { runtime.redact(runtime.session.accessibilitySnapshot()).toByteArray() }
        }
    }

    private suspend fun attach(
        runtime: AgentRuntime,
        stepId: StepId,
        type: ArtifactType,
        bytes: suspend () -> ByteArray,
    ) {
        try {
            val captured =
                withTimeoutOrNull(CAPTURE_TIMEOUT) { bytes() }
                    ?: return logger.warn { "${runtime.identity.agentId}: $type capture timed out after $CAPTURE_TIMEOUT" }
            val record = artifacts.write(runtime.runId, stepId, runtime.identity.agentId.value, type, captured)
            recorder.artifact(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "${runtime.identity.agentId}: could not capture $type for $stepId: ${runtime.redact(e.message.orEmpty())}" }
        }
    }

    companion object {
        val CAPTURE_TIMEOUT = 10.seconds
        private const val MAX_ACTION_CHARS = 300
        private const val MAX_DETAIL_CHARS = 2000
    }
}
