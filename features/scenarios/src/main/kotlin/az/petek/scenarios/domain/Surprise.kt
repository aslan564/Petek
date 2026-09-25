package az.petek.scenarios.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Id of a surprise. It is derived from what the surprise is about (run, scenario step, agent), so collecting the
 * same run again yields the same ids and triage can resume without asking the model twice.
 */
@JvmInline
value class SurpriseId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "SurpriseId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun of(
            runId: RunId,
            scenarioStep: String,
            agentId: AgentId?,
        ): SurpriseId {
            val key = "${runId.value}|$scenarioStep|${agentId?.value ?: "*"}"
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return SurpriseId("srp_" + HexFormat.of().formatHex(digest).take(ID_HEX_CHARS))
        }

        private const val ID_HEX_CHARS = 24
    }
}

/** What made something a surprise; when several apply to one actor's step, the first in this order names it. */
enum class SurpriseKind {
    /** The agent itself stopped with `report_problem`. */
    PROBLEM_REPORTED,

    /** A step (the actor's action, a `wait_for`, an `emits`, a group check) ended FAILED, ERROR or BLOCKED. */
    FAILED_STEP,

    /** The judge produced a finding, or an assertion failed. */
    FINDING,
}

/**
 * Something in a run that did not go as the scenario expected, for one actor in one scenario step
 * ([agentId] null = the step as a whole, e.g. `only_one_succeeds`). Everything the run recorded about that actor in
 * that step belongs to it, so one question to the model covers it and one verdict explains it.
 */
data class Surprise(
    val id: SurpriseId,
    val runId: RunId,
    val scenarioStep: String,
    val agentId: AgentId?,
    val kind: SurpriseKind,
    /** One-line summary for lists, e.g. `a07 reported a problem in read_announce: report_problem bug "…"`. */
    val text: String,
    val evidence: SurpriseEvidence,
)

/**
 * The evidence a surprise is based on: ids the panel links to, and [facts], the redacted lines exactly as the triage
 * question showed them to the model. Each fact starts with the `[id]` it describes.
 */
data class SurpriseEvidence(
    val stepIds: List<StepId>,
    val artifactIds: List<ArtifactId>,
    val findingIds: List<FindingId>,
    val facts: List<String>,
) {
    val refs: List<EvidenceRef>
        get() =
            stepIds.map { EvidenceRef(EvidenceRefType.STEP, it.value) } +
                artifactIds.map { EvidenceRef(EvidenceRefType.ARTIFACT, it.value) } +
                findingIds.map { EvidenceRef(EvidenceRefType.FINDING, it.value) }

    /**
     * The [refs] whose `[id]` appears in the [facts], i.e. the evidence the triage question actually showed. A long
     * action shows only its latest steps and artifacts, so a verdict may only rest on this subset.
     */
    val shownRefs: List<EvidenceRef>
        get() = refs.filter { ref -> facts.any { fact -> fact.contains("[${ref.id}]") } }
}

enum class EvidenceRefType { STEP, ARTIFACT, FINDING }

/** A link from a verdict to one evidence record (a step, an artifact such as a screenshot, a judge finding). */
data class EvidenceRef(
    val type: EvidenceRefType,
    val id: String,
) {
    override fun toString(): String = id
}

/** Why a failing record is not a surprise. */
enum class IgnoreReason {
    /** `permission_denied` in a main step: forbidden-action tests expect the refusal; their assertions decide. */
    EXPECTED_REFUSAL,

    /** A loser of a race whose `only_one_succeeds` check passed: exactly one actor was supposed to win. */
    LOST_RACE,

    /**
     * The test environment failed (test inbox or LLM unreachable); that says nothing about target, model or scenario.
     * The checks run after such a failed action are left out with it: the action they check never happened.
     */
    ENVIRONMENT,

    /**
     * A receiver's `wait_for` timed out for an event no actor published in the whole run: the emitter's own failure
     * explains it and is triaged (or ignored) on its own, so the receivers add nothing but cost.
     */
    NOT_PUBLISHED,
}

/** A failing record left out of triage, with the reason, so the owner can see that nothing was silently dropped. */
data class IgnoredSignal(
    val runId: RunId,
    val scenarioStep: String,
    val agentId: AgentId?,
    val ref: EvidenceRef,
    val reason: IgnoreReason,
    val detail: String,
)

/** Result of collecting a run's surprises. */
data class SurpriseCollection(
    val surprises: List<Surprise>,
    val ignored: List<IgnoredSignal>,
)
