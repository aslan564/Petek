package az.petek.scenarios.domain

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.StepPhase
import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.scenarios.domain.StepConventions.Role

/** What a run recorded, as read from the evidence store. */
data class RunEvidence(
    val steps: List<StepRecord>,
    val assertions: List<AssertionRecord>,
    val findings: List<FindingRecord>,
    val artifacts: List<ArtifactRecord>,
)

/**
 * Finds a run's surprises (pure). Records are grouped per actor and scenario step (see [Surprise]); a group is a
 * surprise when at least one of these holds:
 * - the agent called `report_problem` ([SurpriseKind.PROBLEM_REPORTED]);
 * - a concluding step ended FAILED, ERROR or BLOCKED ([SurpriseKind.FAILED_STEP]); the individual decisions inside a
 *   `do` or sub-actions inside a `run` are evidence, not surprises of their own;
 * - the judge made a finding, or an assertion failed ([SurpriseKind.FINDING]).
 *
 * Expected outcomes are left out and listed as [IgnoredSignal]s instead: `permission_denied` refusals in main steps
 * (forbidden-action tests expect them; in setup a refusal is a real surprise), the losers of a race whose
 * `only_one_succeeds` check passed, and failures of the test environment itself (test inbox or LLM unreachable).
 * The judge's agent-failure finding for such a step is left out with it. A failed assertion or a finding about the
 * target is never left out: an expected refusal whose `http_status` check failed is still a surprise.
 *
 * All text is passed through the [redactor] and clipped, because it goes to the model and into the panel.
 */
class SurpriseCollector(
    private val redactor: TextRedactor,
) {
    fun collect(
        runId: RunId,
        campaign: Campaign,
        evidence: RunEvidence,
    ): SurpriseCollection = Pass(runId, campaign, evidence).run()

    private inner class Pass(
        private val runId: RunId,
        campaign: Campaign,
        private val evidence: RunEvidence,
    ) {
        private val phases: Map<String, StepPhase> = campaign.allSteps.associate { it.id to it.phase }
        private val wonRaces: Set<String> =
            evidence.assertions
                .filter { it.runId == runId && it.agentId == null && it.type == StepConventions.ONLY_ONE_SUCCEEDS }
                .groupBy { it.scenarioStep }
                .filterValues { records -> records.all { it.verdict == Verdict.PASSED } }
                .keys
        private val runArtifacts: List<ArtifactRecord> = evidence.artifacts.filter { it.runId == runId }
        private val artifactsById: Map<ArtifactId, ArtifactRecord> = runArtifacts.associateBy { it.artifactId }
        private val artifactsByStep: Map<StepId, List<ArtifactId>> =
            runArtifacts.groupBy({ it.stepId }, { it.artifactId })

        fun run(): SurpriseCollection {
            val surprises = mutableListOf<Surprise>()
            val ignored = mutableListOf<IgnoredSignal>()
            groups().forEach { group ->
                when (val outcome = judge(group)) {
                    is GroupOutcome.Surprising -> surprises += outcome.surprise
                    is GroupOutcome.Expected -> ignored += outcome.signals
                }
            }
            return SurpriseCollection(surprises, ignored)
        }

        private fun groups(): Collection<Group> {
            val groups = LinkedHashMap<Pair<String, AgentId?>, Group>()

            fun groupOf(
                scenarioStep: String,
                agentId: AgentId?,
            ) = groups.getOrPut(scenarioStep to agentId) { Group(scenarioStep, agentId) }
            evidence.steps.filter { it.runId == runId }.forEach { groupOf(it.scenarioStep, it.agentId).steps += it }
            evidence.assertions.filter { it.runId == runId }.forEach { groupOf(it.scenarioStep, it.agentId).assertions += it }
            evidence.findings.filter { it.runId == runId }.forEach { groupOf(it.scenarioStep, it.agentId).findings += it }
            return groups.values
        }

        private fun judge(group: Group): GroupOutcome {
            val triggers = mutableListOf<Trigger>()
            val ignored = mutableListOf<IgnoredSignal>()
            group.steps.filter(StepConventions::failing).forEach { step ->
                val role = StepConventions.roleOf(step)
                if (role == Role.INTERMEDIATE) return@forEach
                val reason = ignoreReason(group, step)
                when {
                    reason != null -> {
                        ignored +=
                            signal(group, EvidenceRef(EvidenceRefType.STEP, step.stepId.value), reason, step.describe())
                    }

                    role == Role.PROBLEM_REPORT -> {
                        triggers += Trigger.Problem(step)
                    }

                    else -> {
                        triggers += Trigger.FailedStep(step)
                    }
                }
            }
            group.findings.forEach { finding ->
                val reason = if (finding.findingClass == FindingClass.AGENT_FAILURE) ignoreReason(group, sourceOf(group, finding)) else null
                if (reason != null) {
                    ignored += signal(group, EvidenceRef(EvidenceRefType.FINDING, finding.findingId.value), reason, finding.note)
                } else {
                    triggers += Trigger.Finding(finding)
                }
            }
            group.assertions.filter { it.verdict == Verdict.FAILED }.forEach { triggers += Trigger.FailedAssertion(it) }
            if (triggers.isEmpty()) return GroupOutcome.Expected(ignored)
            return GroupOutcome.Surprising(surprise(group, triggers))
        }

        /** Why a failing step (or the finding derived from it) is expected; null when it is a surprise. */
        private fun ignoreReason(
            group: Group,
            step: StepRecord?,
        ): IgnoreReason? {
            val key = step?.let(StepConventions::failureKey)
            return when {
                key in StepConventions.ENVIRONMENT_KEYS -> {
                    IgnoreReason.ENVIRONMENT
                }

                key == StepConventions.PERMISSION_DENIED && step.status == StepStatus.BLOCKED &&
                    phases[step.scenarioStep] == StepPhase.MAIN -> {
                    IgnoreReason.EXPECTED_REFUSAL
                }

                group.agentId != null && group.scenarioStep in wonRaces -> {
                    IgnoreReason.LOST_RACE
                }

                else -> {
                    null
                }
            }
        }

        private fun sourceOf(
            group: Group,
            finding: FindingRecord,
        ): StepRecord? = finding.stepId?.let { id -> group.steps.firstOrNull { it.stepId == id } }

        private fun signal(
            group: Group,
            ref: EvidenceRef,
            reason: IgnoreReason,
            detail: String,
        ) = IgnoredSignal(runId, group.scenarioStep, group.agentId, ref, reason, clip(redactor.redact(detail), MAX_TEXT))

        private fun surprise(
            group: Group,
            triggers: List<Trigger>,
        ): Surprise {
            val kind = triggers.minOf { it.kind }
            val primary = triggers.filter { it.kind == kind }.let { if (kind == SurpriseKind.FINDING) it.first() else it.last() }
            val artifactIds =
                (
                    group.steps.flatMap { step -> artifactsByStep[step.stepId].orEmpty() } +
                        group.assertions.flatMap { it.artifactIds } +
                        group.findings.flatMap { it.artifactIds }
                ).distinct()
            val stepIds = (group.steps.map { it.stepId } + group.assertions.map { it.stepId }).distinct()
            return Surprise(
                id = SurpriseId.of(runId, group.scenarioStep, group.agentId),
                runId = runId,
                scenarioStep = group.scenarioStep,
                agentId = group.agentId,
                kind = kind,
                text = clip(redactor.redact(primary.text(group)), MAX_TEXT),
                evidence =
                    SurpriseEvidence(
                        stepIds = stepIds,
                        artifactIds = artifactIds,
                        findingIds = group.findings.map { it.findingId }.distinct(),
                        facts = facts(group, artifactIds),
                    ),
            )
        }

        private fun facts(
            group: Group,
            artifactIds: List<ArtifactId>,
        ): List<String> =
            buildList {
                val omitted = group.steps.size - MAX_STEP_FACTS
                if (omitted > 0) add("($omitted earlier steps of this actor in this scenario step are not shown)")
                group.steps.takeLast(MAX_STEP_FACTS).forEach { add(stepFact(it)) }
                group.assertions.forEach { add(assertionFact(it)) }
                group.findings.forEach { add(findingFact(it)) }
                artifactIds.takeLast(MAX_ARTIFACT_FACTS).forEach { add(artifactFact(it)) }
            }.map { clip(redactor.redact(it), MAX_FACT) }

        private fun stepFact(step: StepRecord): String =
            buildString {
                append("[${step.stepId}] ${step.kind} ${clip(step.action, MAX_FIELD)} -> ${step.status} (${step.durationMs} ms)")
                step.llmReason?.takeIf { it.isNotBlank() }?.let { append("; agent's reason: ").append(clip(it, MAX_FIELD)) }
                step.detail?.takeIf { it.isNotBlank() }?.let { append("; observed: ").append(clip(it, MAX_FIELD)) }
            }

        private fun assertionFact(assertion: AssertionRecord): String =
            buildString {
                append("[${assertion.stepId}] assertion ${assertion.type} (${assertion.source}) -> ${assertion.verdict}")
                append("; expected: ").append(clip(assertion.expected, MAX_FIELD))
                append("; observed: ").append(clip(assertion.observed ?: "-", MAX_FIELD))
                assertion.latencyMs?.let { append("; latency: $it ms") }
                assertion.note?.takeIf { it.isNotBlank() }?.let { append("; note: ").append(clip(it, MAX_FIELD)) }
            }

        private fun findingFact(finding: FindingRecord): String =
            buildString {
                append("[${finding.findingId}] finding ${finding.findingClass}")
                finding.a?.let { append("; A (sender): ").append(clip(it, MAX_FIELD)) }
                finding.b?.let { append("; B (receiver): ").append(clip(it, MAX_FIELD)) }
                finding.c?.let { append("; C (oracle): ").append(clip(it, MAX_FIELD)) }
                append("; note: ").append(clip(finding.note, MAX_FIELD))
            }

        private fun artifactFact(id: ArtifactId): String {
            val record = artifactsById[id] ?: return "[$id] artifact"
            return "[$id] ${record.type.name.lowercase()} recorded for [${record.stepId}]"
        }
    }

    private class Group(
        val scenarioStep: String,
        val agentId: AgentId?,
    ) {
        val steps = mutableListOf<StepRecord>()
        val assertions = mutableListOf<AssertionRecord>()
        val findings = mutableListOf<FindingRecord>()

        val who: String get() = agentId?.value ?: "the step as a whole"
    }

    private sealed interface GroupOutcome {
        data class Surprising(
            val surprise: Surprise,
        ) : GroupOutcome

        data class Expected(
            val signals: List<IgnoredSignal>,
        ) : GroupOutcome
    }

    private sealed interface Trigger {
        val kind: SurpriseKind

        fun text(group: Group): String

        data class Problem(
            val step: StepRecord,
        ) : Trigger {
            override val kind = SurpriseKind.PROBLEM_REPORTED

            override fun text(group: Group) = "${group.who} reported a problem in ${group.scenarioStep}: ${step.action}"
        }

        data class FailedStep(
            val step: StepRecord,
        ) : Trigger {
            override val kind = SurpriseKind.FAILED_STEP

            override fun text(group: Group) = "${group.who} in ${group.scenarioStep}: ${step.describe()}"
        }

        data class Finding(
            val finding: FindingRecord,
        ) : Trigger {
            override val kind = SurpriseKind.FINDING

            override fun text(group: Group) = "${finding.findingClass} finding in ${group.scenarioStep} for ${group.who}: ${finding.note}"
        }

        data class FailedAssertion(
            val assertion: AssertionRecord,
        ) : Trigger {
            override val kind = SurpriseKind.FINDING

            override fun text(group: Group) =
                "${group.who} in ${group.scenarioStep}: ${assertion.type} failed; expected ${assertion.expected}, " +
                    "observed ${assertion.observed ?: "-"}"
        }
    }

    companion object {
        const val MAX_STEP_FACTS: Int = 25
        const val MAX_ARTIFACT_FACTS: Int = 10
        const val MAX_TEXT: Int = 300
        const val MAX_FIELD: Int = 500
        const val MAX_FACT: Int = 1_600

        private fun StepRecord.describe(): String = "$action -> $status" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

        internal fun clip(
            text: String,
            max: Int,
        ): String {
            val oneLine = text.replace('\n', ' ').replace('\r', ' ')
            return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
        }
    }
}
