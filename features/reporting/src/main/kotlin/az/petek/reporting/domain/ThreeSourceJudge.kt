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

package az.petek.reporting.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.IdGenerator
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.EvidenceTier
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.Verdict

/**
 * The [Judge] of docs/PLAN.md design decision 4: correctness is decided by three sources agreeing, never by an
 * opinion. Per (scenario step, actor) the assertions are folded into A (sender), B (receiver screen; harness checks
 * such as `latency_max` count here) and C (oracle); SKIPPED, NOT_APPLICABLE and INCONCLUSIVE assertions carry no
 * evidence and count as absent. A group whose only non-passing checks are INCONCLUSIVE yields one INCONCLUSIVE finding
 * (the "tool gap" shelf) with what each check lacked (Faza 24.12).
 *
 * Rule order matters and is the contract: a failing oracle with a sender that did its part blames the backend,
 * an oracle that confirms while receivers did not see blames delivery/UI, everything else needs a human.
 *
 * The step overload adds one finding per (scenario step, agent, failure key) for agent actions that failed with a
 * key ([FailureKeys.of]): `mail_timeout` is BACKEND (no e-mail was sent), `request_failed` INVESTIGATE (the target
 * turned down a racer's own request although its agent claimed success), a site defect a deterministic check saw
 * ([SITE_DEFECTS]: `unhealthy_page`, `access_not_refused`, `forbidden_accepted`) a SITE_CHECK finding about the site,
 * with the check's own words, any other key AGENT_FAILURE. An environment
 * problem such as `mail_unavailable` (the test inbox was unreachable) is an AGENT_FAILURE whose note says so, never a
 * finding about the target. An expected refusal (`permission_denied` in a forbidden-action test) and a lost race
 * (`lost_race`, including the loser agent's own records of that action) are not failures and yield none
 * ([ExpectedOutcomes]).
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
    ): List<FindingRecord> = judgeGroups(run, assertions, actions = emptyMap())

    override fun findings(
        run: RunRecord,
        assertions: List<AssertionRecord>,
        steps: List<StepRecord>,
    ): List<FindingRecord> = judgeGroups(run, assertions, actionsOf(run, steps)) + stepFindings(run, steps)

    private fun judgeGroups(
        run: RunRecord,
        assertions: List<AssertionRecord>,
        actions: Map<GroupKey, String>,
    ): List<FindingRecord> =
        assertions
            .filter { it.runId == run.runId }
            .groupBy { GroupKey(it.scenarioStep, it.agentId) }
            .mapNotNull { (key, group) -> judgeGroup(run, key, group, actions[key]) }

    /**
     * What each actor did in each step as its own record says (`do: <task> -> PASSED: <summary>`, `run <function> ->
     * …`): A of a finding whose step has no check of the sender's own, so "what the sender did" is never blank
     * (docs/PLAN.md decision 4: A is the sender's step log). It is shown only; the checks alone classify.
     */
    private fun actionsOf(
        run: RunRecord,
        steps: List<StepRecord>,
    ): Map<GroupKey, String> =
        steps
            .filter { it.runId == run.runId && it.agentId != null && ExpectedOutcomes.isWholeAction(it) }
            .associate { step ->
                GroupKey(step.scenarioStep, step.agentId) to
                    compact("${step.action} -> ${step.status}" + (step.detail?.let { ": $it" } ?: ""))
            }

    private fun judgeGroup(
        run: RunRecord,
        key: GroupKey,
        group: List<AssertionRecord>,
        action: String?,
    ): FindingRecord? {
        val evidence = group.filter { it.verdict !in NO_EVIDENCE }
        val a = observe(SOURCE_A, evidence.filter { it.source == EvidenceSource.SENDER })
        val b = observe(SOURCE_B, evidence.filter { it.source == EvidenceSource.RECEIVER || it.source == EvidenceSource.HARNESS })
        val c = observe(SOURCE_C, evidence.filter { it.source == EvidenceSource.ORACLE })
        val verdict =
            classify(a, b, c) as? JudgeVerdict.Finding
                ?: return inconclusive(run, key, group.filter { it.verdict == Verdict.INCONCLUSIVE })
        val failed = evidence.filter { it.verdict == Verdict.FAILED }
        return FindingRecord(
            findingId = ids.findingId(),
            runId = run.runId,
            stepId = (failed.firstOrNull() ?: evidence.first()).stepId,
            scenarioStep = key.scenarioStep,
            agentId = key.agentId,
            findingClass = verdict.findingClass,
            a = a?.value ?: action,
            b = b?.value,
            c = c?.value,
            note = noteWithDetails(verdict.note, failed.mapNotNull { it.note }),
            artifactIds = evidence.flatMap { it.artifactIds }.distinct(),
            evidenceTier = if (c != null) EvidenceTier.ORACLE_CONFIRMED else EvidenceTier.UI_NETWORK,
        )
    }

    /**
     * Checks that ran but could not decide ([Verdict.INCONCLUSIVE], Faza 24.12) and nothing that failed: one finding on
     * the "tool gap" shelf with what each check lacked, never a finding about the site.
     */
    private fun inconclusive(
        run: RunRecord,
        key: GroupKey,
        records: List<AssertionRecord>,
    ): FindingRecord? {
        if (records.isEmpty()) return null
        return FindingRecord(
            findingId = ids.findingId(),
            runId = run.runId,
            stepId = records.first().stepId,
            scenarioStep = key.scenarioStep,
            agentId = key.agentId,
            findingClass = FindingClass.INCONCLUSIVE,
            a = observe(SOURCE_A, records.filter { it.source == EvidenceSource.SENDER })?.value,
            b = observe(SOURCE_B, records.filter { it.source == EvidenceSource.RECEIVER || it.source == EvidenceSource.HARNESS })?.value,
            c = observe(SOURCE_C, records.filter { it.source == EvidenceSource.ORACLE })?.value,
            note = noteWithDetails(NOTE_INCONCLUSIVE, records.mapNotNull { it.note }),
            artifactIds = records.flatMap { it.artifactIds }.distinct(),
            evidenceTier = EvidenceTier.UI_NETWORK,
        )
    }

    private fun stepFindings(
        run: RunRecord,
        steps: List<StepRecord>,
    ): List<FindingRecord> {
        val expected = ExpectedOutcomes(steps.filter { it.runId == run.runId })
        return steps
            .asSequence()
            .filter { it.runId == run.runId && it.agentId != null && it.kind !in NOT_AGENT_ACTIONS }
            .mapNotNull { step -> expected.failureKey(step)?.let { key -> step to key } }
            .distinctBy { (step, key) -> Triple(step.scenarioStep, step.agentId, key) }
            .map { (step, key) -> stepFinding(run, step, key) }
            .toList()
    }

    private fun stepFinding(
        run: RunRecord,
        step: StepRecord,
        key: String,
    ): FindingRecord {
        val noMail = key == FailureKeys.MAIL_TIMEOUT
        val refused = key == FailureKeys.REQUEST_FAILED
        val defect = SITE_DEFECTS[key]
        return FindingRecord(
            findingId = ids.findingId(),
            runId = run.runId,
            stepId = step.stepId,
            scenarioStep = step.scenarioStep,
            agentId = step.agentId,
            findingClass =
                when {
                    noMail -> FindingClass.BACKEND
                    refused -> FindingClass.INVESTIGATE
                    defect != null -> defect
                    else -> FindingClass.AGENT_FAILURE
                },
            a = compact(step.action),
            b = step.detail?.let(::compact),
            c = if (noMail) NO_EMAIL_SENT else null,
            note = if (defect != null) "$SITE_DEFECT_NOTE ($key)." else stepNote(key, noMail),
            artifactIds = emptyList(),
            // A `do` step failed because the model said so; a deterministic step failed on what the harness saw, and so
            // did a `do` step whose failure code found in the browser's requests (a site defect such as forbidden_accepted).
            evidenceTier =
                if (step.kind == StepKind.DO && !noMail && !refused && defect == null) EvidenceTier.LLM_JUDGED else EvidenceTier.UI_NETWORK,
        )
    }

    private fun stepNote(
        key: String,
        noMail: Boolean,
    ): String {
        if (noMail) return "$NO_EMAIL_SENT ($key)."
        if (key == FailureKeys.REQUEST_FAILED) return NOTE_REQUEST_FAILED
        val environment = FailureKeys.environmentProblem(key) ?: return "Agent failure: $key."
        return "Agent failure: $key ($environment: an environment problem, not an error of the target)."
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

        /**
         * Failure keys of deterministic checks that saw the site itself go wrong: a blind `site_health` problem (a page
         * wider than a phone, a script error, a broken link, a slow request, a back button that leads elsewhere), a
         * page `direct_url` found open to a role that must not see it, and a forbidden action the site accepted from
         * the tester's own page (`forbidden_accepted`). The agent only carried the check, so these are never a tool gap.
         */
        val SITE_DEFECTS: Map<String, FindingClass> =
            mapOf(
                FailureKeys.UNHEALTHY_PAGE to FindingClass.SITE_CHECK,
                FailureKeys.ACCESS_NOT_REFUSED to FindingClass.SITE_CHECK,
                FailureKeys.FORBIDDEN_ACCEPTED to FindingClass.SITE_CHECK,
            )
        const val SITE_DEFECT_NOTE = "The site failed a check that code made on what the browser saw"

        /**
         * Records that are not the agent's own action: an ASSERT is judged through its assertion records, and a
         * WAIT that timed out (`not_received`) is the harness waiting for an event its emitter never published,
         * which the emitter's own failure and the latency table's missing receivers already explain.
         */
        val NOT_AGENT_ACTIONS = setOf(StepKind.ASSERT, StepKind.WAIT)

        const val NO_EMAIL_SENT = "no e-mail sent"
        const val NOTE_BACKEND = "The target (C) contradicts what the sender did (A): backend error."
        const val NOTE_DELIVERY = "The target (C) confirms the change but the receiver (B) did not see it: delivery or UI error."
        const val NOTE_SENDER_ONLY = "The sender's check (A) failed and there is no receiver or oracle evidence to attribute it."
        const val NOTE_NO_ORACLE = "The receiver (B) disagrees and there is no oracle evidence (C) to attribute it."
        const val NOTE_DISAGREE = "The sources disagree in a way the three-source rule cannot attribute."
        const val NOTE_INCONCLUSIVE =
            "The check ran but its evidence could not decide it: a gap of the test or its scenario, not a defect of the site."

        /** Verdicts that carry no evidence for the three sources: not run, not applicable, or not decidable. */
        val NO_EVIDENCE = setOf(Verdict.SKIPPED, Verdict.NOT_APPLICABLE, Verdict.INCONCLUSIVE)

        const val NOTE_REQUEST_FAILED =
            "The target turned down the actor's own request (B shows it) although its agent reported success (request_failed)."
    }
}
