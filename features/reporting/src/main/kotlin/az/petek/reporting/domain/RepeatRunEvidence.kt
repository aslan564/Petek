package az.petek.reporting.domain

import az.petek.core.ids.RunId
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.StepRecord

/** The evidence of one run of a `--repeat` group that stability is computed from. */
data class RepeatRunEvidence(
    val runId: RunId,
    val assertions: List<AssertionRecord>,
    val steps: List<StepRecord>,
)
