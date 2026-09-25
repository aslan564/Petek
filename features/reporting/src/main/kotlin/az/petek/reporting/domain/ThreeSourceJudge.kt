package az.petek.reporting.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.IdGenerator
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.Verdict

/**
 * The [Judge] of docs/PLAN.md design decision 4: correctness is decided by three sources agreeing, never by an
 * opinion. Per (scenario step, actor) the assertions are folded into A (sender), B (receiver screen; harness checks
 * such as `latency_max` count here) and C (oracle); SKIPPED assertions carry no evidence and count as absent.
 *
 * Rule order matters and is the contract: a failing oracle with a sender that did its part blames the backend,
 * an oracle that confirms while receivers did not see blames delivery/UI, everything else needs a human.
 */
class ThreeSourceJudge(
    private val ids: IdGenerator,
) : Judge {
    override fun classify(
        a: Observation?,
        b: Observation?,
        c: Observation?,
    ): JudgeVerdict {
        val sa = a.state()
        val sb = b.state()
        val sc = c.state()
        if (listOf(sa, sb, sc).none { it == SourceState.FAILED }) return JudgeVerdict.Pass
        val verdict =
            when {
                sc == SourceState.FAILED && sa != SourceState.FAILED -> {
                    FindingClass.BACKEND to NOTE_BACKEND
                }

                sc == SourceState.PASSED && sb == SourceState.FAILED -> {
                    FindingClass.DELIVERY_UI to NOTE_DELIVERY
                }

                sa == SourceState.FAILED && sb == SourceState.ABSENT && sc == SourceState.ABSENT -> {
                    FindingClass.INVESTIGATE to NOTE_SENDER_ONLY
                }

                sb == SourceState.FAILED && sc == SourceState.ABSENT -> {
                    FindingClass.INVESTIGATE to NOTE_NO_ORACLE
                }

                else -> {
                    FindingClass.INVESTIGATE to NOTE_DISAGREE
                }
            }
        return JudgeVerdict.Finding(verdict.first, verdict.second)
    }

    override fun findings(
        run: RunRecord,
        assertions: List<AssertionRecord>,
    ): List<FindingRecord> =
        assertions
            .filter { it.runId == run.runId }
            .groupBy { GroupKey(it.scenarioStep, it.agentId) }
            .mapNotNull { (key, group) -> judgeGroup(run, key, group) }

    override fun findings(
        run: RunRecord,
        assertions: List<AssertionRecord>,
        steps: List<StepRecord>,
    ): List<FindingRecord> = findings(run, assertions) + stepFindings(run, steps)

    private fun judgeGroup(
        run: RunRecord,
        key: GroupKey,
        group: List<AssertionRecord>,
    ): FindingRecord? {
        val evidence = group.filter { it.verdict != Verdict.SKIPPED }
        val a = observe(SOURCE_A, evidence.filter { it.source == EvidenceSource.SENDER })
        val b = observe(SOURCE_B, evidence.filter { it.source == EvidenceSource.RECEIVER || it.source == EvidenceSource.HARNESS })
        val c = observe(SOURCE_C, evidence.filter { it.source == EvidenceSource.ORACLE })
        val verdict = classify(a, b, c) as? JudgeVerdict.Finding ?: return null
        val failed = evidence.filter { it.verdict == Verdict.FAILED }
        return FindingRecord(
            findingId = ids.findingId(),
            runId = run.runId,
            stepId = (failed.firstOrNull() ?: evidence.first()).stepId,
            scenarioStep = key.scenarioStep,
            agentId = key.agentId,
            findingClass = verdict.findingClass,
            a = a?.value,
            b = b?.value,
            c = c?.value,
            note = noteWithDetails(verdict.note, failed.mapNotNull { it.note }),
            artifactIds = evidence.flatMap { it.artifactIds }.distinct(),
        )
    }

    private fun stepFindings(
        run: RunRecord,
        steps: List<StepRecord>,
    ): List<FindingRecord> =
        steps
            .asSequence()
            .filter { it.runId == run.runId && it.agentId != null && it.kind != StepKind.ASSERT }
            .mapNotNull { step -> FailureKeys.of(step)?.let { key -> step to key } }
            .distinctBy { (step, key) -> Triple(step.scenarioStep, step.agentId, key) }
            .map { (step, key) -> stepFinding(run, step, key) }
            .toList()

    private fun stepFinding(
        run: RunRecord,
        step: StepRecord,
        key: String,
    ): FindingRecord {
        val noMail = key == FailureKeys.MAIL_TIMEOUT
        return FindingRecord(
            findingId = ids.findingId(),
            runId = run.runId,
            stepId = step.stepId,
            scenarioStep = step.scenarioStep,
            agentId = step.agentId,
            findingClass = if (noMail) FindingClass.BACKEND else FindingClass.AGENT_FAILURE,
            a = compact(step.action),
            b = step.detail?.let(::compact),
            c = if (noMail) NO_EMAIL_SENT else null,
            note = if (noMail) "$NO_EMAIL_SENT ($key)." else "Agent failure: $key.",
            artifactIds = emptyList(),
        )
    }

    private fun observe(
        source: String,
        records: List<AssertionRecord>,
    ): Observation? {
        if (records.isEmpty()) return null
        val failed = records.filter { it.verdict == Verdict.FAILED }
        val shown = failed.ifEmpty { records }
        return Observation(
            source = source,
            value = shown.joinToString("; ") { "${compact(it.expected)} -> ${it.observed?.let(::compact) ?: NONE}" },
            passed = failed.isEmpty(),
        )
    }

    private fun noteWithDetails(
        note: String,
        details: List<String>,
    ): String {
        val extra = details.map(::compact).distinct()
        return if (extra.isEmpty()) note else note + " Details: " + extra.joinToString("; ")
    }

    private fun compact(text: String): String {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        return if (oneLine.length <= MAX_VALUE_LENGTH) oneLine else oneLine.take(MAX_VALUE_LENGTH - 1) + "…"
    }

    private fun Observation?.state(): SourceState =
        when (this?.passed) {
            null -> SourceState.ABSENT
            true -> SourceState.PASSED
            false -> SourceState.FAILED
        }

    /** An observation with `passed == null` (e.g. an unavailable oracle) is as good as none. */
    private enum class SourceState { ABSENT, PASSED, FAILED }

    private data class GroupKey(
        val scenarioStep: String,
        val agentId: AgentId?,
    )

    private companion object {
        const val SOURCE_A = "A"
        const val SOURCE_B = "B"
        const val SOURCE_C = "C"
        const val NONE = "(none)"
        const val MAX_VALUE_LENGTH = 200
        val WHITESPACE = Regex("\\s+")

        const val NO_EMAIL_SENT = "no e-mail sent"
        const val NOTE_BACKEND = "The target (C) contradicts what the sender did (A): backend error."
        const val NOTE_DELIVERY = "The target (C) confirms the change but the receiver (B) did not see it: delivery or UI error."
        const val NOTE_SENDER_ONLY = "The sender's check (A) failed and there is no receiver or oracle evidence to attribute it."
        const val NOTE_NO_ORACLE = "The receiver (B) disagrees and there is no oracle evidence (C) to attribute it."
        const val NOTE_DISAGREE = "The sources disagree in a way the three-source rule cannot attribute."
    }
}
