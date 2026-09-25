package az.petek.verification.application

import az.petek.campaign.domain.AssertionSpec
import az.petek.evidence.domain.AssertionRecord
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionInput

/**
 * Evaluates a step's assertions and records each result as evidence. Every browser-based verdict gets a
 * screenshot artifact and every oracle/HTTP verdict gets the response body artifact (CLAUDE.md rule 5).
 */
interface VerifyStepUseCase {
    suspend fun verifyActor(
        specs: List<AssertionSpec>,
        input: AssertionInput,
    ): List<AssertionRecord>

    suspend fun verifyGroup(
        specs: List<AssertionSpec>,
        input: AssertionInput,
        results: List<ActorResult>,
    ): List<AssertionRecord>
}
