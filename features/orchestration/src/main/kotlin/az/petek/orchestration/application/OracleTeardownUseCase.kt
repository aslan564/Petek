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

package az.petek.orchestration.application

import az.petek.core.ids.RunId
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.RunResource
import az.petek.oracle.domain.TargetOracle

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
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                if (alreadyGone(resource.externalId)) null else e.message ?: e::class.simpleName
            }
        if (problem == null) runs.removeResource(resource.runId, resource.kind, resource.externalId)
        return problem
    }

    private suspend fun alreadyGone(companyId: String): Boolean =
        try {
            oracle.company(companyId) == null
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            false
        }

    companion object {
        /** Resource kind of the test company a run created (registered by the runner). */
        const val COMPANY = "company"
    }
}
