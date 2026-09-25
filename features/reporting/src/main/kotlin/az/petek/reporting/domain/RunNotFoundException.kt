package az.petek.reporting.domain

import az.petek.core.error.PetekException
import az.petek.core.ids.RunId

/** A report was requested for a run the evidence store does not know (e.g. a mistyped `petek report <run_id>`). */
class RunNotFoundException(
    val runId: RunId,
) : PetekException("Run '$runId' not found in the evidence store")
