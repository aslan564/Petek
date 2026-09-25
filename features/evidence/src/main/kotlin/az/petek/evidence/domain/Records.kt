package az.petek.evidence.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import java.time.Instant

/*
 * The evidence model (docs/PLAN.md "Sübut bazası"). No verdict exists without evidence (CLAUDE.md rule 5):
 * every assertion links to at least one artifact (screenshot) or an oracle response.
 */

enum class RunResult { RUNNING, PASSED, FAILED, ABORTED }

data class RunRecord(
    val runId: RunId,
    val runTag: RunTag,
    val campaignHash: String,
    val campaignName: String,
    val seed: Long,
    val target: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val result: RunResult = RunResult.RUNNING,
    /** Runs started together by `--repeat N` share a group id. */
    val repeatGroup: String? = null,
    val repeatIndex: Int? = null,
)

/** External objects a run created on the target (e.g. the test company) so teardown can remove them. */
data class RunResource(
    val runId: RunId,
    val kind: String,
    val externalId: String,
    val createdAt: Instant,
)

enum class StepKind { DO, RUN, WAIT, EMIT, ASSERT, SYSTEM }

enum class StepStatus { PASSED, FAILED, SKIPPED, BLOCKED, ERROR }

/** One action of one agent (or of the harness when [agentId] is null). */
data class StepRecord(
    val stepId: StepId,
    val runId: RunId,
    val agentId: AgentId?,
    /** Scenario step id from the campaign, e.g. `announce`. */
    val scenarioStep: String,
    val kind: StepKind,
    /** Human-readable action, e.g. `click #12 "Elan yarat"` or `run register_and_login`. */
    val action: String,
    /** The LLM's stated reason for a `do` action; null for deterministic steps. */
    val llmReason: String?,
    val startedAt: Instant,
    val endedAt: Instant,
    val durationMs: Long,
    val status: StepStatus,
    val detail: String?,
    val correlationId: CorrelationId,
)

data class EventRecord(
    val eventId: EventId,
    val runId: RunId,
    val name: String,
    val emitter: AgentId,
    val objectId: String?,
    /** Where [objectId] came from: `url_regex`, `oracle`, `dom`, `agent_report`. */
    val objectIdSource: String?,
    val payloadJson: String,
    val t0: Instant,
)

/** A receiver seeing (or not seeing) an event on screen. [latencyMs] = t1 − t0 measured by the harness. */
data class EventReceipt(
    val eventId: EventId,
    val runId: RunId,
    val receiver: AgentId,
    val received: Boolean,
    val t1: Instant?,
    val latencyMs: Long?,
)

enum class ArtifactType(
    val extension: String,
) {
    SCREENSHOT("png"),
    A11Y("yaml"),
    DOM("html"),
    HTTP("json"),
    MAIL("json"),
    ORACLE("json"),
    PROMPT("txt"),
    LOG("txt"),
}

data class ArtifactRecord(
    val artifactId: ArtifactId,
    val runId: RunId,
    val stepId: StepId,
    val type: ArtifactType,
    /** Path relative to the evidence root, e.g. `run_…/a07/0003-screenshot.png`. */
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Source of truth for the three-source comparison: A sender log, B receiver screen, C target oracle. */
enum class EvidenceSource { SENDER, RECEIVER, ORACLE, HARNESS }

enum class Verdict { PASSED, FAILED, SKIPPED }

data class AssertionRecord(
    val stepId: StepId,
    val runId: RunId,
    val agentId: AgentId?,
    val scenarioStep: String,
    val type: String,
    val source: EvidenceSource,
    val expected: String,
    val observed: String?,
    val verdict: Verdict,
    val latencyMs: Long?,
    val note: String?,
    val artifactIds: List<ArtifactId>,
)

enum class FindingClass { BACKEND, DELIVERY_UI, INVESTIGATE, FLAKY, AGENT_FAILURE }

data class FindingRecord(
    val findingId: FindingId,
    val runId: RunId,
    val stepId: StepId?,
    val scenarioStep: String,
    val agentId: AgentId?,
    val findingClass: FindingClass,
    /** What the sender did (A), what receivers saw (B), what the oracle says (C). */
    val a: String?,
    val b: String?,
    val c: String?,
    val note: String,
    val artifactIds: List<ArtifactId>,
)

/** Token and cost accounting per agent (LLM usage is reported, never estimated by the LLM). */
data class UsageRecord(
    val runId: RunId,
    val agentId: AgentId,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val costUsd: Double?,
    val calls: Int,
)
