package az.petek.verification.domain

import az.petek.campaign.domain.AssertionSpec

/**
 * True for assertions judged once per step over all actors' results (`only_one_succeeds`) rather than per actor.
 * It decides which assertions `VerifyStepUseCase.verifyGroup` handles; `verifyActor` handles the rest.
 * The `when` is exhaustive on purpose: a new assertion type must choose its scope.
 */
val AssertionSpec.isGroupLevel: Boolean
    get() =
        when (this) {
            AssertionSpec.OnlyOneSucceeds -> true

            is AssertionSpec.VisibleText,
            is AssertionSpec.NotVisible,
            is AssertionSpec.Oracle,
            is AssertionSpec.HttpStatus,
            is AssertionSpec.Count,
            is AssertionSpec.LatencyMax,
            -> false
        }
