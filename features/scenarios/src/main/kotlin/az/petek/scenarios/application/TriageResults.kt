package az.petek.scenarios.application

import az.petek.core.ids.RunId
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.TriageRepository
import az.petek.scenarios.domain.TriageVerdict

/** Read side of triage for the panel: the verdicts of a run, and why a triage draft exists. */
class TriageResults(
    private val triage: TriageRepository,
) {
    /** Every stored surprise of [runId] in collection order, with its verdict and last failure. */
    suspend fun forRun(runId: RunId): List<TriageItem> {
        val verdicts = triage.verdicts(runId).associateBy { it.surpriseId }
        val failures = triage.failures(runId).associateBy { it.surpriseId }
        return triage.surprises(runId).map { TriageItem(it, verdicts[it.id], failures[it.id]) }
    }

    /** The verdicts whose proposals make up the draft [draftId]. */
    suspend fun forDraft(draftId: ScenarioVersionId): List<TriageVerdict> = triage.verdictsForDraft(draftId)
}
