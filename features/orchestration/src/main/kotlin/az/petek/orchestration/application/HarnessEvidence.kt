package az.petek.orchestration.application

import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.AssertionSpec
import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The runner's own evidence: step records for harness decisions (waits, emits, skips, teardown) with timings taken
 * from the harness clock (CLAUDE.md rule 1), and failure screenshots when the agent could not leave evidence itself.
 */
internal class HarnessEvidence(
    private val recorder: EvidenceRecorder,
    private val artifacts: ArtifactStore,
    private val ids: IdGenerator,
    private val clock: HarnessClock,
) {
    suspend fun step(
        run: RunState,
        agentId: AgentId?,
        scenarioStep: String,
        kind: StepKind,
        action: String,
        startedAt: HarnessTimestamp,
        status: StepStatus,
        detail: String?,
        correlationId: CorrelationId,
        tally: Tally,
        stepId: StepId = ids.stepId(),
        /** When the recorded work ended; now by default. Given when the record is written later than that. */
        endedAt: HarnessTimestamp = clock.now(),
    ): StepId {
        recorder.step(
            StepRecord(
                stepId = stepId,
                runId = run.runId,
                agentId = agentId,
                scenarioStep = scenarioStep,
                kind = kind,
                action = action,
                llmReason = null,
                startedAt = startedAt.wall,
                endedAt = endedAt.wall,
                durationMs = startedAt.elapsedUntil(endedAt).inWholeMilliseconds.coerceAtLeast(0),
                status = status,
                detail = detail,
                correlationId = correlationId,
            ),
        )
        run.tally.step(tally, agentId)
        return stepId
    }

    /** Harness-level record; not counted in the run summary unless [tally] says so. */
    suspend fun system(
        run: RunState,
        agentId: AgentId?,
        action: String,
        status: StepStatus,
        detail: String?,
        scenarioStep: String = HARNESS_STEP,
        tally: Tally = Tally.NONE,
    ): StepId =
        step(
            run = run,
            agentId = agentId,
            scenarioStep = scenarioStep,
            kind = StepKind.SYSTEM,
            action = action,
            startedAt = clock.now(),
            status = status,
            detail = detail,
            correlationId = ids.correlationId(),
            tally = tally,
        )

    /**
     * Records assertions that were not evaluated (e.g. the awaited event never arrived) as SKIPPED, so the report
     * shows them instead of silently dropping them.
     */
    suspend fun skippedAssertions(
        run: RunState,
        stepId: StepId,
        scenarioStep: String,
        agentId: AgentId?,
        specs: List<AssertionSpec>,
        reason: String,
    ) {
        specs.forEach { spec ->
            recorder.assertion(
                AssertionRecord(
                    stepId = stepId,
                    runId = run.runId,
                    agentId = agentId,
                    scenarioStep = scenarioStep,
                    type = spec.type,
                    source = EvidenceSource.HARNESS,
                    expected = describe(spec),
                    observed = null,
                    verdict = Verdict.SKIPPED,
                    latencyMs = null,
                    note = reason,
                    artifactIds = emptyList(),
                ),
            )
        }
    }

    /** Best-effort screenshot of [session] linked to [stepId]; a broken session only costs the picture. */
    suspend fun screenshot(
        run: RunState,
        stepId: StepId,
        agentId: AgentId,
        session: BrowserSession,
    ): ArtifactId? =
        try {
            val record = artifacts.write(run.runId, stepId, agentId.value, ArtifactType.SCREENSHOT, session.screenshot())
            recorder.artifact(record)
            record.artifactId
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            logger.warn { "run ${run.runId} agent $agentId: failure screenshot not taken (${e::class.simpleName}: ${e.message})" }
            null
        }

    companion object {
        /** Scenario-step name used for harness records that belong to no campaign step. */
        const val HARNESS_STEP = "harness"

        fun describe(spec: AssertionSpec): String =
            when (spec) {
                is AssertionSpec.VisibleText -> "visible_text '${spec.text}' within ${spec.within}"
                is AssertionSpec.NotVisible -> "not_visible ${spec.text?.let { "'$it'" } ?: spec.selector}"
                is AssertionSpec.Oracle -> "oracle ${spec.path} ${spec.field.orEmpty()}".trim()
                is AssertionSpec.HttpStatus -> "http_status ${spec.method} ${spec.path} == ${spec.equals}"
                is AssertionSpec.Count -> "count ${spec.selector} == ${spec.equals}"
                is AssertionSpec.LatencyMax -> "latency_max ${spec.max}"
                is AssertionSpec.OnlyOneSucceeds -> "only_one_succeeds" + (spec.request?.let { " ${it.describe()}" } ?: "")
            }
    }
}
