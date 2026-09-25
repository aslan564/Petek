package az.petek.reporting

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.domain.Verdict
import java.time.Instant

/** Compact builders for evidence records, so each test states only what it is about. */
object ReportTestData {
    val RUN_ID = RunId("run_test")
    val START: Instant = Instant.parse("2026-01-01T10:00:00Z")

    fun run(
        runId: RunId = RUN_ID,
        startedAt: Instant = START,
        endedAt: Instant? = START.plusSeconds(245),
        result: RunResult = RunResult.FAILED,
        repeatGroup: String? = null,
        repeatIndex: Int? = null,
        campaignName: String = "kadrohr-core",
        target: String = "https://staging.kadrohr.test",
    ) = RunRecord(
        runId = runId,
        runTag = RunTag("k7x2"),
        campaignHash = "c0ffee",
        campaignName = campaignName,
        seed = 42,
        target = target,
        startedAt = startedAt,
        endedAt = endedAt,
        result = result,
        repeatGroup = repeatGroup,
        repeatIndex = repeatIndex,
    )

    fun assertion(
        scenarioStep: String,
        agent: String?,
        source: EvidenceSource,
        verdict: Verdict,
        expected: String = "expected",
        observed: String? = "observed",
        type: String = "visible_text",
        stepId: String = "stp_${scenarioStep}_${agent ?: "group"}",
        note: String? = null,
        artifacts: List<String> = emptyList(),
        runId: RunId = RUN_ID,
    ) = AssertionRecord(
        stepId = StepId(stepId),
        runId = runId,
        agentId = agent?.let(::AgentId),
        scenarioStep = scenarioStep,
        type = type,
        source = source,
        expected = expected,
        observed = observed,
        verdict = verdict,
        latencyMs = null,
        note = note,
        artifactIds = artifacts.map(::ArtifactId),
    )

    fun step(
        scenarioStep: String,
        agent: String?,
        status: StepStatus = StepStatus.PASSED,
        kind: StepKind = StepKind.DO,
        detail: String? = null,
        action: String = "do $scenarioStep",
        stepId: String = "stp_${scenarioStep}_${agent ?: "harness"}",
        startOffsetMs: Long = 0,
        durationMs: Long = 1_000,
        runId: RunId = RUN_ID,
    ) = StepRecord(
        stepId = StepId(stepId),
        runId = runId,
        agentId = agent?.let(::AgentId),
        scenarioStep = scenarioStep,
        kind = kind,
        action = action,
        llmReason = null,
        startedAt = START.plusMillis(startOffsetMs),
        endedAt = START.plusMillis(startOffsetMs + durationMs),
        durationMs = durationMs,
        status = status,
        detail = detail,
        correlationId = CorrelationId("cor_$stepId"),
    )

    fun event(
        id: String,
        name: String = "announcement_created",
        objectId: String? = "42",
        emitter: String = "a01",
        runId: RunId = RUN_ID,
    ) = EventRecord(
        eventId = EventId(id),
        runId = runId,
        name = name,
        emitter = AgentId(emitter),
        objectId = objectId,
        objectIdSource = "oracle",
        payloadJson = "{}",
        t0 = START,
    )

    fun receipt(
        eventId: String,
        receiver: String,
        latencyMs: Long?,
        received: Boolean = latencyMs != null,
        runId: RunId = RUN_ID,
    ) = EventReceipt(
        eventId = EventId(eventId),
        runId = runId,
        receiver = AgentId(receiver),
        received = received,
        t1 = latencyMs?.let { START.plusMillis(it) },
        latencyMs = latencyMs,
    )

    fun usage(
        agent: String,
        input: Long,
        output: Long,
        cost: Double?,
        cacheRead: Long = 0,
        calls: Int = 1,
        runId: RunId = RUN_ID,
    ) = UsageRecord(runId, AgentId(agent), input, output, cacheRead, cost, calls)

    fun finding(
        id: String,
        scenarioStep: String,
        agent: String?,
        findingClass: FindingClass = FindingClass.DELIVERY_UI,
        a: String? = null,
        b: String? = "Sabah 10:00 ümumi iclas -> (none)",
        c: String? = "published -> published",
        note: String = "The target (C) confirms the change but the receiver (B) did not see it: delivery or UI error.",
        artifacts: List<String> = emptyList(),
        runId: RunId = RUN_ID,
    ) = FindingRecord(
        findingId = FindingId(id),
        runId = runId,
        stepId = StepId("stp_${scenarioStep}_$agent"),
        scenarioStep = scenarioStep,
        agentId = agent?.let(::AgentId),
        findingClass = findingClass,
        a = a,
        b = b,
        c = c,
        note = note,
        artifactIds = artifacts.map(::ArtifactId),
    )
}
