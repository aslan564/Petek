package az.petek.scenarios.application

import az.petek.campaign.domain.Campaign
import az.petek.evidence.domain.RunRecord
import az.petek.scenarios.domain.IgnoredSignal
import az.petek.scenarios.domain.RunEvidence
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.Surprise
import az.petek.scenarios.domain.TriageFailure
import az.petek.scenarios.domain.TriageVerdict

/**
 * Limits of one triage execution.
 * - [maxQuestions]: at most this many surprises are asked about per execution (cost control); the rest are
 *   [TriageReport.deferred] and asked by the next execution.
 * - [parallelism]: questions in flight at once (the LLM client may limit further).
 * - [rationaleLanguage]: the owner reads the rationale in the panel, so it is written in the owner's language.
 */
data class TriageOptions(
    val maxQuestions: Int = 50,
    val parallelism: Int = 4,
    val rationaleLanguage: String = "Azerbaijani",
    val maxOutputTokens: Int = 4_096,
) {
    init {
        require(maxQuestions >= 0) { "maxQuestions must not be negative, was $maxQuestions" }
        require(parallelism >= 1) { "parallelism must be at least 1, was $parallelism" }
        require(rationaleLanguage.isNotBlank()) { "rationaleLanguage must not be blank" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive, was $maxOutputTokens" }
    }
}

/** One surprise with what triage made of it so far: a verdict, a failed attempt, both (a failed re-triage), or neither. */
data class TriageItem(
    val surprise: Surprise,
    val verdict: TriageVerdict?,
    val failure: TriageFailure?,
)

/** What triage would ask about, without asking (no LLM call, nothing stored). */
data class TriagePreview(
    val run: RunRecord,
    val scenario: ScenarioVersion,
    val surprises: List<Surprise>,
    val ignored: List<IgnoredSignal>,
)

/**
 * Result of one triage execution. [items] covers every stored surprise of the run (also those decided by earlier
 * executions); [asked] is the number of questions sent this time, [deferred] the surprises left for a later
 * execution by [TriageOptions.maxQuestions]. [draft] is the v2 DRAFT built from this run's usable proposals, if any.
 */
data class TriageReport(
    val run: RunRecord,
    val scenario: ScenarioVersion,
    val items: List<TriageItem>,
    val ignored: List<IgnoredSignal>,
    val asked: Int,
    val deferred: Int,
    val draft: ScenarioVersion?,
)

/** Everything one triage execution works on. */
internal data class TriageContext(
    val run: RunRecord,
    val version: ScenarioVersion,
    val campaign: Campaign,
    val evidence: RunEvidence,
)
