package az.petek.identity.application

import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRegistryGenerator
import az.petek.identity.domain.IdentityRepository
import az.petek.identity.domain.IdentitySpec

/** Generates the identity registry for a run and stores it. Nothing is executed against the target. */
class PlanIdentitiesUseCase(
    private val generator: IdentityRegistryGenerator,
    private val repository: IdentityRepository,
) {
    suspend fun execute(
        runId: RunId,
        runTag: RunTag,
        spec: IdentitySpec,
    ): IdentityPlan {
        val plan = generator.generate(spec, runTag)
        repository.replaceAll(runId, plan)
        return plan
    }
}
