package az.petek.reporting.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.domain.Verdict
import az.petek.reporting.domain.AgentDirectory
import az.petek.reporting.domain.FailedAgentRow
import az.petek.reporting.domain.FailureKeys
import az.petek.reporting.domain.LatencyStatistics
import az.petek.reporting.domain.RepeatRunEvidence
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportSummary
import az.petek.reporting.domain.RunNotFoundException
import az.petek.reporting.domain.StabilityAnalyzer
import az.petek.reporting.domain.StabilityRow
import az.petek.reporting.domain.StepRow
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Collects everything the report shows for one run from the evidence store (docs/PLAN.md "Sübut bazası və
 * hesabat"). It only reads: findings must already be recorded (see [FinalizeRunUseCase]), so `petek report` can
 * rebuild the report of an old run without judging it again.
 *
 * [agents] supplies display names (the identity feature is not visible from here); [stabilityAnalyzer] is used
 * when the run belongs to a `--repeat` group.
 */
class BuildReportUseCase(
    private val runs: RunRepository,
    private val query: EvidenceQuery,
    private val artifacts: ArtifactStore,
    private val agents: AgentDirectory = AgentDirectory.NONE,
    private val stabilityAnalyzer: StabilityAnalyzer = StabilityAnalyzer(),
) {
    /** @throws RunNotFoundException when [runId] is unknown. */
    suspend fun build(runId: RunId): ReportModel {
        val run = runs.find(runId) ?: throw RunNotFoundException(runId)
        val steps = query.steps(runId)
        val assertions = query.assertions(runId)
        val artifactRecords = query.artifacts(runId)
        val usage = query.usage(runId)
        val names = agents.names(runId)
        val stepRows = stepRows(steps, artifactRecords, names)
        return ReportModel(
            run = run,
            summary = summary(run, steps, stepRows, assertions, usage),
            steps = stepRows,
            assertions = assertions,
            latency = LatencyStatistics.compute(query.events(runId), query.receipts(runId)),
            findings = query.findings(runId),
            failedAgents = failedAgents(steps, names),
            stability = stability(run),
            artifactLinks = artifactLinks(runId, artifactRecords),
            usage = usage,
        )
    }

    private fun stepRows(
        steps: List<StepRecord>,
        artifactRecords: List<ArtifactRecord>,
        names: Map<AgentId, String>,
    ): List<StepRow> {
        val lastScreenshot =
            artifactRecords
                .filter { it.type == ArtifactType.SCREENSHOT }
                .associateBy { it.stepId }
        return steps
            .filter { it.kind in TABLE_KINDS }
            .map { step ->
                StepRow(
                    scenarioStep = step.scenarioStep,
                    agentId = step.agentId?.value,
                    agentName = step.agentId?.let { names[it] },
                    kind = step.kind.name,
                    status = step.status.name,
                    durationMs = step.durationMs,
                    detail = step.detail,
                    screenshot = lastScreenshot[step.stepId]?.artifactId?.value,
                )
            }
    }

    private fun summary(
        run: RunRecord,
        steps: List<StepRecord>,
        rows: List<StepRow>,
        assertions: List<AssertionRecord>,
        usage: List<UsageRecord>,
    ): ReportSummary {
        val failingStatuses = FailureKeys.FAILING_STATUSES.map { it.name }.toSet()
        val agents = steps.mapNotNull { it.agentId } + assertions.mapNotNull { it.agentId } + usage.map { it.agentId }
        return ReportSummary(
            stepsPassed = rows.count { it.status == StepStatus.PASSED.name },
            stepsFailed = rows.count { it.status in failingStatuses },
            assertionsPassed = assertions.count { it.verdict == Verdict.PASSED },
            assertionsFailed = assertions.count { it.verdict == Verdict.FAILED },
            assertionsSkipped = assertions.count { it.verdict == Verdict.SKIPPED },
            agents = agents.distinct().size,
            durationMs = durationMs(run, steps),
            inputTokens = usage.sumOf { it.inputTokens },
            outputTokens = usage.sumOf { it.outputTokens },
            costUsd = usage.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
            realtimeTransports = realtimeTransports(steps),
        )
    }

    /** A run that is still open (or crashed) is measured up to its last recorded step. */
    private fun durationMs(
        run: RunRecord,
        steps: List<StepRecord>,
    ): Long {
        val end = run.endedAt ?: steps.maxOfOrNull { it.endedAt } ?: return 0
        return Duration.between(run.startedAt, end).toMillis().coerceAtLeast(0)
    }

    private fun realtimeTransports(steps: List<StepRecord>): List<String> =
        steps
            .filter { it.kind == StepKind.SYSTEM && it.action == NETWORK_OBSERVATION }
            .flatMap { it.detail.orEmpty().split(',') }
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun failedAgents(
        steps: List<StepRecord>,
        names: Map<AgentId, String>,
    ): List<FailedAgentRow> =
        steps
            .filter { it.agentId != null && it.kind != StepKind.ASSERT && it.status in FailureKeys.FAILING_STATUSES }
            .groupBy { requireNotNull(it.agentId) to it.scenarioStep }
            .map { (key, failures) ->
                val (agentId, scenarioStep) = key
                FailedAgentRow(
                    agentId = agentId.value,
                    name = names[agentId] ?: agentId.value,
                    scenarioStep = scenarioStep,
                    reason = failures.map(::reason).distinct().joinToString(", "),
                )
            }.sortedBy { AgentId(it.agentId).index }

    private fun reason(step: StepRecord): String =
        FailureKeys.of(step)
            ?: step.detail
                ?.replace(WHITESPACE, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (it.length <= MAX_REASON_LENGTH) it else it.take(MAX_REASON_LENGTH - 1) + "…" }
            ?: step.status.name.lowercase()

    private suspend fun stability(run: RunRecord): List<StabilityRow>? {
        val group = run.repeatGroup ?: return null
        val members =
            runs
                .byRepeatGroup(group)
                .ifEmpty { listOf(run) }
                .sortedWith(compareBy({ it.repeatIndex ?: Int.MAX_VALUE }, { it.startedAt }))
        return stabilityAnalyzer.analyze(
            members.map { RepeatRunEvidence(it.runId, query.assertions(it.runId), query.steps(it.runId)) },
        )
    }

    private fun artifactLinks(
        runId: RunId,
        records: List<ArtifactRecord>,
    ): Map<String, String> {
        val reportDirectory = ReportLayout.directory(artifacts, runId).normalize()
        return records.associate { it.artifactId.value to link(reportDirectory, runId, it) }
    }

    /** Relative from the report directory to the file; falls back to the store's own layout rule. */
    private fun link(
        reportDirectory: Path,
        runId: RunId,
        record: ArtifactRecord,
    ): String =
        try {
            reportDirectory.relativize(artifacts.resolve(record).normalize()).invariantSeparatorsPathString
        } catch (_: IllegalArgumentException) {
            "../" + record.relativePath.replace('\\', '/').removePrefix("$runId/")
        }

    private companion object {
        val TABLE_KINDS = setOf(StepKind.DO, StepKind.RUN, StepKind.WAIT)

        /** SYSTEM step recorded by the browser feature; its detail lists the transports seen, e.g. `SSE,POLLING`. */
        const val NETWORK_OBSERVATION = "network_observation"
        const val MAX_REASON_LENGTH = 200
        val WHITESPACE = Regex("\\s+")
    }
}
