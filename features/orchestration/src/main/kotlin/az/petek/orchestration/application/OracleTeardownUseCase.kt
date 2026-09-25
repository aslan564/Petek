package az.petek.orchestration.application

import az.petek.core.ids.RunId
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResource
import az.petek.oracle.domain.TargetOracle
import kotlinx.coroutines.CancellationException

/**
 * Removes what a run created on the target, using the resources the run registered (CLAUDE.md rule 8: the oracle
 * itself refuses companies that are not `is_test`). Works for finished runs and for runs that crashed half-way, and
 * is idempotent: a removed resource is unregistered, and a company that is already gone counts as removed.
 *
 * Failures never throw; they are returned so the caller can report them. With `runId == null` the latest run is used.
 */
class OracleTeardownUseCase(
    private val runs: RunRepository,
    private val oracle: TargetOracle,
) : TeardownUseCase {
    override suspend fun teardown(runId: RunId?): TeardownResult {
        val id = runId ?: runs.latest()?.runId ?: return TeardownResult(null, emptyList(), emptyList())
        val removed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (resource in runs.resources(id)) {
            val label = "${resource.kind}:${resource.externalId}"
            when (val problem = remove(resource)) {
                null -> removed += label
                else -> failures += "$label: $problem"
            }
        }
        return TeardownResult(id, removed, failures)
    }

    /** Returns null when the resource is gone (and unregistered), otherwise the reason it could not be removed. */
    private suspend fun remove(resource: RunResource): String? {
        if (resource.kind != COMPANY) return "no teardown is known for resource kind '${resource.kind}'"
        if (!oracle.isAvailable) return "the target test API is not available (is PETEK_TEST_TOKEN set?)"
        val problem =
            try {
                oracle.deleteCompany(resource.externalId)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (alreadyGone(resource.externalId)) null else e.message ?: e::class.simpleName
            }
        if (problem == null) runs.removeResource(resource.runId, resource.kind, resource.externalId)
        return problem
    }

    private suspend fun alreadyGone(companyId: String): Boolean =
        try {
            oracle.company(companyId) == null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        /** Resource kind of the test company a run created (registered by the runner). */
        const val COMPANY = "company"
    }
}
