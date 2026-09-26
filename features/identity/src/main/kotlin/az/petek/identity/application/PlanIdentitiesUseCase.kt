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
