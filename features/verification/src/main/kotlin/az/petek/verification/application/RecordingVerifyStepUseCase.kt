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

package az.petek.verification.application

import az.petek.campaign.domain.AssertionSpec
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.IdGenerator
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.EvidenceSource
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionEvaluator
import az.petek.verification.domain.AssertionInput
import az.petek.verification.domain.AssertionResult
import az.petek.verification.domain.AssertionText
import az.petek.verification.domain.isGroupLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Evaluates assertions with an [AssertionEvaluator] and records every result linked to evidence (CLAUDE.md rule 5):
 * - RECEIVER and HARNESS results share one SCREENSHOT of the actor's session per call, taken after the checks ran
 *   (`latency_max` is backed by the same screen as the `visible_text` it measures).
 * - A result carrying the target's raw answer stores it: `oracle` as ORACLE, `http_status` as HTTP, others as LOG;
 *   a result that also carries an oracle answer ([AssertionResult.oracleEvidence], the oracle condition of
 *   `only_one_succeeds`) stores that as a second, ORACLE artifact.
 * - Results still without evidence (no session, screenshot failed, empty answer, skipped check) link to one LOG
 *   artifact per call in which the harness states what was expected, what was observed and why nothing else exists.
 *   So every recorded assertion, whatever its verdict, has at least one artifact.
 *
 * Each artifact is recorded through [recorder] before the assertion that references it. [verifyActor] ignores
 * group-level assertions ([isGroupLevel]); [verifyGroup] evaluates only those, so a step's assertion list can be
 * passed to both unchanged. Failures to store evidence propagate: a verdict that cannot be backed by evidence is
 * not recorded. Stateless apart from its ports, so actors may verify concurrently.
 */
class RecordingVerifyStepUseCase(
    private val evaluator: AssertionEvaluator,
    private val recorder: EvidenceRecorder,
    private val artifacts: ArtifactStore,
    private val ids: IdGenerator,
) : VerifyStepUseCase {
    override suspend fun verifyActor(
        specs: List<AssertionSpec>,
        input: AssertionInput,
    ): List<AssertionRecord> {
        val perActor = specs.filterNot { it.isGroupLevel }
        if (perActor.isEmpty()) return emptyList()
        return record(evaluator.evaluate(perActor, input), input)
    }

    override suspend fun verifyGroup(
        specs: List<AssertionSpec>,
        input: AssertionInput,
        results: List<ActorResult>,
    ): List<AssertionRecord> {
        val groupLevel = specs.filter { it.isGroupLevel }
        if (groupLevel.isEmpty()) return emptyList()
        return record(groupLevel.map { evaluateGroupLevel(it, results, input) }, input)
    }

    private suspend fun evaluateGroupLevel(
        spec: AssertionSpec,
        results: List<ActorResult>,
        input: AssertionInput,
    ): AssertionResult =
        when (spec) {
            is AssertionSpec.OnlyOneSucceeds -> evaluator.evaluateOnlyOneSucceeds(spec, results, input)
            else -> throw IllegalArgumentException("${spec.type} is not a group-level assertion")
        }

    private suspend fun record(
        results: List<AssertionResult>,
        input: AssertionInput,
    ): List<AssertionRecord> {
        if (results.isEmpty()) return emptyList()
        val verificationId = ids.correlationId()
        val owner = input.agentId?.value ?: HARNESS_OWNER
        logger.debug { "verification $verificationId: ${results.size} result(s) for $owner at ${input.scenarioStep}" }

        val screenshot = if (results.any { it.wantsScreenshot }) captureScreenshot(input, owner) else Screenshot.NOT_TAKEN
        val direct = results.map { directEvidence(it, screenshot, input, owner) }
        val unbacked = results.filterIndexed { i, _ -> direct[i].isEmpty() }
        val harnessLog =
            if (unbacked.isEmpty()) {
                null
            } else {
                store(input, owner, ArtifactType.LOG, harnessLog(verificationId, input, unbacked, screenshot))
            }

        return results.mapIndexed { i, result ->
            val record =
                AssertionRecord(
                    stepId = input.stepId,
                    runId = input.runId,
                    agentId = input.agentId,
                    scenarioStep = input.scenarioStep,
                    type = result.spec.type,
                    source = result.source,
                    expected = result.expected,
                    observed = result.observed,
                    verdict = result.verdict,
                    latencyMs = result.latency?.inWholeMilliseconds,
                    note = noteFor(result, screenshot),
                    artifactIds = direct[i].ifEmpty { listOfNotNull(harnessLog) },
                )
            recorder.assertion(record)
            record
        }
    }

    private suspend fun directEvidence(
        result: AssertionResult,
        screenshot: Screenshot,
        input: AssertionInput,
        owner: String,
    ): List<ArtifactId> =
        buildList {
            if (result.wantsScreenshot) screenshot.artifactId?.let(::add)
            result.rawEvidence
                ?.takeIf { it.isNotBlank() }
                ?.let { add(store(input, owner, rawEvidenceType(result.spec), it.toByteArray())) }
            result.oracleEvidence
                ?.takeIf { it.isNotBlank() }
                ?.let { add(store(input, owner, ArtifactType.ORACLE, it.toByteArray())) }
        }

    /** Result of trying to capture the actor's screen once for this call. */
    private class Screenshot(
        val artifactId: ArtifactId?,
        val failure: String?,
    ) {
        companion object {
            val NOT_TAKEN = Screenshot(null, null)
        }
    }

    private suspend fun captureScreenshot(
        input: AssertionInput,
        owner: String,
    ): Screenshot {
        // Without a session the evaluator has already failed those results with a note; nothing to capture.
        val session = input.session ?: return Screenshot.NOT_TAKEN
        val bytes =
            try {
                session.screenshot()
            } catch (e: CancellationException) {
                // Only the caller's own cancellation aborts; one leaking out of the session is a failed screenshot.
                currentCoroutineContext().ensureActive()
                return screenshotFailed(e, input, owner)
            } catch (e: Exception) {
                return screenshotFailed(e, input, owner)
            }
        return Screenshot(store(input, owner, ArtifactType.SCREENSHOT, bytes), null)
    }

    private fun screenshotFailed(
        e: Exception,
        input: AssertionInput,
        owner: String,
    ): Screenshot {
        logger.warn(e) { "screenshot failed for $owner at ${input.scenarioStep}" }
        return Screenshot(null, "screenshot unavailable: ${AssertionText.error(e)}")
    }

    private suspend fun store(
        input: AssertionInput,
        owner: String,
        type: ArtifactType,
        bytes: ByteArray,
    ): ArtifactId {
        val artifact = artifacts.write(input.runId, input.stepId, owner, type, bytes)
        recorder.artifact(artifact)
        return artifact.artifactId
    }

    private fun noteFor(
        result: AssertionResult,
        screenshot: Screenshot,
    ): String? {
        val screenshotFailure = screenshot.failure.takeIf { result.wantsScreenshot }
        return listOfNotNull(result.note, screenshotFailure).joinToString("; ").ifEmpty { null }
    }

    private fun harnessLog(
        verificationId: CorrelationId,
        input: AssertionInput,
        results: List<AssertionResult>,
        screenshot: Screenshot,
    ): ByteArray =
        buildString {
            appendLine("Petek verification log $verificationId")
            appendLine(
                "run ${input.runId} | step ${input.stepId} | scenario step ${input.scenarioStep} | " +
                    "actor ${input.agentId?.value ?: HARNESS_OWNER}",
            )
            appendLine("No screenshot or target answer backs the results below; this log written by the harness does.")
            results.forEachIndexed { i, result ->
                appendLine()
                appendLine("[${i + 1}] ${result.spec.type} ${result.verdict} (${result.source})")
                appendLine("    expected: ${result.expected}")
                appendLine("    observed: ${result.observed ?: "-"}")
                noteFor(result, screenshot)?.let { appendLine("    note: $it") }
            }
        }.toByteArray()

    private val AssertionResult.wantsScreenshot: Boolean
        get() = source == EvidenceSource.RECEIVER || source == EvidenceSource.HARNESS

    private fun rawEvidenceType(spec: AssertionSpec): ArtifactType =
        when (spec) {
            is AssertionSpec.Oracle -> ArtifactType.ORACLE
            is AssertionSpec.HttpStatus -> ArtifactType.HTTP
            else -> ArtifactType.LOG
        }

    private companion object {
        /** Artifact owner for group-level results, which belong to no single actor. */
        const val HARNESS_OWNER = "harness"

        val logger = KotlinLogging.logger {}
    }
}
