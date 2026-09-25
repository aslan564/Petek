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
        val tableSteps = steps.filter { it.kind in TABLE_KINDS }
        return ReportModel(
            run = run,
            summary = summary(run, steps, tableSteps, assertions, usage),
            steps = stepRows(tableSteps, steps, artifactRecords, names),
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
        tableSteps: List<StepRecord>,
        allSteps: List<StepRecord>,
        artifactRecords: List<ArtifactRecord>,
        names: Map<AgentId, String>,
    ): List<StepRow> {
        val screenshots = Screenshots(allSteps, artifactRecords)
        return tableSteps.map { step ->
            StepRow(
                scenarioStep = step.scenarioStep,
                agentId = step.agentId?.value,
                agentName = step.agentId?.let { names[it] },
                kind = step.kind.name,
                status = step.status.name,
                durationMs = step.durationMs,
                detail = step.detail,
                screenshot = screenshots.lastFor(step)?.artifactId?.value,
            )
        }
    }

    private fun summary(
        run: RunRecord,
        steps: List<StepRecord>,
        tableSteps: List<StepRecord>,
        assertions: List<AssertionRecord>,
        usage: List<UsageRecord>,
    ): ReportSummary {
        val agents = steps.mapNotNull { it.agentId } + assertions.mapNotNull { it.agentId } + usage.map { it.agentId }
        return ReportSummary(
            // An expected refusal is the outcome the forbidden-action step asked for.
            stepsPassed = tableSteps.count { it.status == StepStatus.PASSED || FailureKeys.isExpectedRefusal(it) },
            stepsFailed = tableSteps.count(FailureKeys::isFailure),
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
            .filter { it.agentId != null && it.kind != StepKind.ASSERT && FailureKeys.isFailure(it) }
            .groupBy { requireNotNull(it.agentId) to it.scenarioStep }
            .map { (key, failures) ->
                val (agentId, scenarioStep) = key
                FailedAgentRow(
                    agentId = agentId.value,
                    name = names[agentId] ?: agentId.value,
                    scenarioStep = scenarioStep,
                    reason = failures.map(::reason).distinct().joinToString(", "),
                )
            }.sortedBy { AgentId(it.agentId) }

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
        return records
            .mapNotNull { record -> link(reportDirectory, runId, record)?.let { record.artifactId.value to it } }
            .toMap()
    }

    /**
     * Relative from the report directory to the file. When the store cannot resolve the record, or its path cannot
     * be related to the report directory, the store's layout rule (`<runId>/<owner>/<file>`) is applied to the
     * recorded path; a recorded path that does not follow it (another run, `..` segments) gets no link at all.
     */
    private fun link(
        reportDirectory: Path,
        runId: RunId,
        record: ArtifactRecord,
    ): String? =
        try {
            reportDirectory.relativize(artifacts.resolve(record).normalize()).invariantSeparatorsPathString
        } catch (_: IllegalArgumentException) {
            layoutLink(runId, record.relativePath)
        }

    private fun layoutLink(
        runId: RunId,
        relativePath: String,
    ): String? {
        val path = relativePath.replace('\\', '/')
        val prefix = "$runId/"
        if (!path.startsWith(prefix)) return null
        val inRun = path.removePrefix(prefix)
        val segments = inRun.split('/')
        return if (segments.any { it.isEmpty() || it == "." || it == ".." }) null else "../$inRun"
    }

    /**
     * The screenshot a step row links to: the last one taken in that step record or, for a record without one of its
     * own (the orchestrator's per-actor summary of an action, a wait), the last one the same agent took in the same
     * scenario step up to that record's end. So every row with an outcome points at evidence (CLAUDE.md rule 5).
     */
    private class Screenshots(
        steps: List<StepRecord>,
        artifacts: List<ArtifactRecord>,
    ) {
        private val shots = artifacts.filter { it.type == ArtifactType.SCREENSHOT }
        private val lastOfStep = shots.associateBy { it.stepId }
        private val takenIn = steps.associateBy { it.stepId }
        private val byActorStep = shots.filter { it.stepId in takenIn }.groupBy { takenIn.getValue(it.stepId).actorStep() }

        fun lastFor(step: StepRecord): ArtifactRecord? =
            lastOfStep[step.stepId]
                ?: byActorStep[step.actorStep()]?.lastOrNull { !takenIn.getValue(it.stepId).endedAt.isAfter(step.endedAt) }

        private fun StepRecord.actorStep() = agentId to scenarioStep
    }

    private companion object {
        val TABLE_KINDS = setOf(StepKind.DO, StepKind.RUN, StepKind.WAIT)

        /** SYSTEM step recorded by the browser feature; its detail lists the transports seen, e.g. `SSE,POLLING`. */
        const val NETWORK_OBSERVATION = "network_observation"
        const val MAX_REASON_LENGTH = 200
        val WHITESPACE = Regex("\\s+")
    }
}
