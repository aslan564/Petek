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

package az.petek.reporting.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ABORT_ACTION
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.CAPACITY_ACTION
import az.petek.evidence.domain.COVERAGE_ACTION
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.NotReached
import az.petek.evidence.domain.ROLL_CALL_ACTION
import az.petek.evidence.domain.ROSTER_ACTION
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UNCOVERED_ACTION
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.domain.Verdict
import az.petek.reporting.domain.AgentDirectory
import az.petek.reporting.domain.ExpectedOutcomes
import az.petek.reporting.domain.FailedAgentRow
import az.petek.reporting.domain.FailureKeys
import az.petek.reporting.domain.LatencyStatistics
import az.petek.reporting.domain.NotReachedRow
import az.petek.reporting.domain.PageSpeedRow
import az.petek.reporting.domain.RepeatRunEvidence
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportSummary
import az.petek.reporting.domain.RollCall
import az.petek.reporting.domain.RunNotFoundException
import az.petek.reporting.domain.StabilityAnalyzer
import az.petek.reporting.domain.StabilityRow
import az.petek.reporting.domain.StepRow
import az.petek.reporting.domain.UncoveredRow
import java.time.Duration

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
        // The roll call's own records are rows too: a tester × step it did not get to, a step nobody ran.
        val tableSteps =
            steps.filter { it.kind in TABLE_KINDS || it.action in ROLL_CALL_ACTIONS || isWaveGap(it) || isLeftOut(it) }
        val expected = ExpectedOutcomes(steps)
        return ReportModel(
            run = run,
            summary = summary(run, steps, tableSteps, assertions, usage, expected),
            steps = stepRows(tableSteps, steps, artifactRecords, names, expected),
            assertions = assertions,
            latency = LatencyStatistics.compute(query.events(runId), query.receipts(runId)),
            findings = withStepEvidence(query.findings(runId), steps, artifactRecords),
            failedAgents = failedAgents(steps, names, expected),
            stability = stability(run),
            artifactLinks = artifactLinks(runId, artifactRecords),
            usage = usage,
            coverage =
                steps
                    .filter { it.kind == StepKind.SYSTEM && it.action == COVERAGE_ACTION }
                    .flatMap { it.detail.orEmpty().lines() }
                    .filter { it.isNotBlank() },
            pageSpeed = PageSpeedRow.of(query.pageTimings(runId)),
            rollCall = rollCall(run, steps, assertions, usage, names),
        )
    }

    private fun rollCall(
        run: RunRecord,
        steps: List<StepRecord>,
        assertions: List<AssertionRecord>,
        usage: List<UsageRecord>,
        names: Map<AgentId, String>,
    ): RollCall {
        val harness = steps.filter { it.kind == StepKind.SYSTEM && it.agentId == null }
        val planned =
            harness
                .lastOrNull { it.action == ROSTER_ACTION }
                ?.detail
                ?.substringAfter(": ", "")
                ?.split(", ")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val acted = actingAgents(steps, assertions, usage).map { it.value }
        val notReached =
            steps
                .filter { it.kind == StepKind.SYSTEM && it.agentId != null }
                .mapNotNull { record -> notReached(record, names) }
                .distinctBy { it.agentId to it.scenarioStep }
                .sortedWith(compareBy({ AgentId(it.agentId) }, { it.scenarioStep }))
        return RollCall(
            planned = planned.sortedBy(::AgentId),
            acted = acted.sortedBy(::AgentId),
            notReached = notReached,
            uncovered =
                harness
                    .filter { it.action == UNCOVERED_ACTION || isWaveGap(it) }
                    .map {
                        UncoveredRow(
                            it.scenarioStep,
                            it.detail
                                .orEmpty()
                                .substringAfter(':')
                                .trim(),
                        )
                    },
            abortReason =
                harness
                    .lastOrNull { it.action == ABORT_ACTION }
                    ?.detail
                    ?.substringAfter("run aborted: ")
                    ?.substringBeforeLast("; steps not run: ")
                    ?.trim(),
            overCapacity = harness.lastOrNull { it.action == CAPACITY_ACTION && it.detail.orEmpty().startsWith(OVER_CAPACITY) }?.detail,
            // Only a concluded run with its roster and a closed roll call says nobody is missing; absence proves nothing.
            recorded = run.result != RunResult.RUNNING && planned.isNotEmpty() && harness.any { it.action == ROLL_CALL_ACTION },
        )
    }

    /**
     * A tester × step without a result of its own: the roll call's `not_reached` record, or the runner's `skip` of a
     * tester out since an earlier failure (its gate, its browser) in a step that began without it.
     */
    private fun notReached(
        record: StepRecord,
        names: Map<AgentId, String>,
    ): NotReachedRow? {
        val agentId = record.agentId ?: return null
        val detail = record.detail.orEmpty()
        val (key, reason) =
            when {
                record.action == NOT_REACHED_ACTION -> {
                    detail.substringBefore(':').trim() to detail.substringAfter(':', detail).trim()
                }

                record.action == SKIP_ACTION && detail.startsWith(FAILED_EARLIER_DETAIL) -> {
                    NotReached.FAILED_EARLIER to detail.removePrefix(FAILED_EARLIER_DETAIL).trim().removeSurrounding("(", ")")
                }

                else -> {
                    return null
                }
            }
        return NotReachedRow(agentId.value, names[agentId] ?: agentId.value, record.scenarioStep, key, reason)
    }

    /** The runner's record of a tester out since an earlier failure in a step that began without it: a row too. */
    private fun isLeftOut(step: StepRecord): Boolean =
        step.kind == StepKind.SYSTEM && step.agentId != null && step.action == SKIP_ACTION &&
            step.detail.orEmpty().startsWith(FAILED_EARLIER_DETAIL)

    /** A wave's receivers that no wave could serve: the runner's agent-less FAILED `coverage` record (`not_covered`). */
    private fun isWaveGap(step: StepRecord): Boolean =
        step.kind == StepKind.SYSTEM && step.agentId == null && step.action == WAVE_COVERAGE && step.status == StepStatus.FAILED

    /** Testers with an action of their own: a step, a check or an AI call, never the harness's records about them. */
    private fun actingAgents(
        steps: List<StepRecord>,
        assertions: List<AssertionRecord>,
        usage: List<UsageRecord>,
    ): Set<AgentId> =
        (
            steps.filter { it.kind != StepKind.SYSTEM }.mapNotNull { it.agentId } +
                assertions.mapNotNull { it.agentId } + usage.map { it.agentId }
        ).toSet()

    /**
     * A finding made from a step's failure names no artifact of its own; it gets that step's last screenshot, so every
     * finding points at its evidence (AGENTS.md rule 5).
     */
    private fun withStepEvidence(
        findings: List<FindingRecord>,
        steps: List<StepRecord>,
        artifactRecords: List<ArtifactRecord>,
    ): List<FindingRecord> {
        val screenshots = Screenshots(steps, artifactRecords)
        val byId = steps.associateBy { it.stepId }
        return findings.map { finding ->
            if (finding.artifactIds.isNotEmpty()) return@map finding
            val shot = finding.stepId?.let(byId::get)?.let(screenshots::lastFor) ?: return@map finding
            finding.copy(artifactIds = listOf(shot.artifactId))
        }
    }

    private fun stepRows(
        tableSteps: List<StepRecord>,
        allSteps: List<StepRecord>,
        artifactRecords: List<ArtifactRecord>,
        names: Map<AgentId, String>,
        expected: ExpectedOutcomes,
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
                lostRace = expected.showsLostRace(step),
                refused = expected.showsRefusal(step),
            )
        }
    }

    private fun summary(
        run: RunRecord,
        steps: List<StepRecord>,
        tableSteps: List<StepRecord>,
        assertions: List<AssertionRecord>,
        usage: List<UsageRecord>,
        expected: ExpectedOutcomes,
    ): ReportSummary {
        val agents = actingAgents(steps, assertions, usage)
        return ReportSummary(
            // An expected refusal or a lost race is the outcome the step asked for.
            stepsPassed = tableSteps.count { it.status == StepStatus.PASSED || expected.isExpected(it) },
            stepsFailed = tableSteps.count(expected::isFailure),
            assertionsPassed = assertions.count { it.verdict == Verdict.PASSED },
            assertionsFailed = assertions.count { it.verdict == Verdict.FAILED },
            assertionsSkipped = assertions.count { it.verdict == Verdict.SKIPPED },
            assertionsNotApplicable = assertions.count { it.verdict == Verdict.NOT_APPLICABLE },
            assertionsInconclusive = assertions.count { it.verdict == Verdict.INCONCLUSIVE },
            agents = agents.size,
            durationMs = durationMs(run, steps),
            inputTokens = usage.sumOf { it.inputTokens },
            outputTokens = usage.sumOf { it.outputTokens },
            costUsd = usage.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
            realtimeTransports = realtimeTransports(steps),
            cacheReadTokens = usage.sumOf { it.cacheReadTokens },
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
        expected: ExpectedOutcomes,
    ): List<FailedAgentRow> =
        steps
            // A step a tester did not get to is the roll call's, never the tester's failure.
            .filter { it.agentId != null && it.kind != StepKind.ASSERT && it.action != NOT_REACHED_ACTION && expected.isFailure(it) }
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
    ): Map<String, String> =
        records
            .mapNotNull { record -> ReportLayout.link(artifacts, runId, record)?.let { record.artifactId.value to it } }
            .toMap()

    /**
     * The screenshot a step row links to: the last one taken in that step record or, for a record without one of its
     * own (the orchestrator's per-actor summary of an action, a wait), the last one the same agent took in the same
     * scenario step up to that record's end. So every row with an outcome points at evidence (AGENTS.md rule 5). A page
     * look's sub-action links its own main frame ([ArtifactType.VISUAL], recorded first); no other row borrows a look.
     */
    private class Screenshots(
        steps: List<StepRecord>,
        artifacts: List<ArtifactRecord>,
    ) {
        private val shots = artifacts.filter { it.type == ArtifactType.SCREENSHOT }
        private val lastOfStep =
            artifacts.filter { it.type == ArtifactType.VISUAL }.groupBy { it.stepId }.mapValues { it.value.first() } +
                shots.associateBy { it.stepId }
        private val takenIn = steps.associateBy { it.stepId }
        private val byActorStep = shots.filter { it.stepId in takenIn }.groupBy { takenIn.getValue(it.stepId).actorStep() }

        fun lastFor(step: StepRecord): ArtifactRecord? =
            lastOfStep[step.stepId]
                ?: byActorStep[step.actorStep()]?.lastOrNull { !takenIn.getValue(it.stepId).endedAt.isAfter(step.endedAt) }

        private fun StepRecord.actorStep() = agentId to scenarioStep
    }

    private companion object {
        val TABLE_KINDS = setOf(StepKind.DO, StepKind.RUN, StepKind.WAIT)
        val ROLL_CALL_ACTIONS = setOf(NOT_REACHED_ACTION, UNCOVERED_ACTION)

        /** The runner's record of a tester it left out of a step that began (`StepExecutor.recordSkippedFailedActors`). */
        const val SKIP_ACTION = "skip"
        const val FAILED_EARLIER_DETAIL = "agent failed earlier"

        /** The runner's record of which receivers of a wave could wait for their event (`reportCoverage`). */
        const val WAVE_COVERAGE = "coverage"
        const val OVER_CAPACITY = "over_capacity"

        /** SYSTEM step recorded by the browser feature; its detail lists the transports seen, e.g. `SSE,POLLING`. */
        const val NETWORK_OBSERVATION = "network_observation"
        const val MAX_REASON_LENGTH = 200
        val WHITESPACE = Regex("\\s+")
    }
}
